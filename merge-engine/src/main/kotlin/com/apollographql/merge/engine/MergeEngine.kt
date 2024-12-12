package com.apollographql.merge.engine

import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.http.HttpBody
import com.apollographql.apollo.api.http.HttpMethod
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.api.json.*
import com.apollographql.apollo.ast.*
import com.apollographql.apollo.network.http.HttpEngine
import com.apollographql.apollo.network.http.HttpInterceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.BufferedSink
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource.Monotonic.markNow
import kotlin.time.toDuration

/**
 * An [HttpInterceptor] that merges multiple queries together and sends all of them at once to the server.
 *
 * [MergeEngine] does not merge mutations/subscriptions
 *
 * No field merging validation is made. if 2 operations use conflicting arguments or aliases they will fail.
 * No variables validation is made. if 2 operations use conflicting variables they will fail.
 * No fragment validation is made: if 2 operations use conflicting fragments they will fail.
 * operation directives are dismissed
 */
class MergeEngine(private val delegate: HttpEngine, val interval: Long) : HttpEngine {
  private val startMark = markNow()
  private val scope = CoroutineScope(Dispatchers.Default)
  private val mutex = Mutex()
  private var job: Job? = null

  // protected by [mutex]
  private val pendingOperations = mutableListOf<PendingOperation>()

  override suspend fun execute(request: HttpRequest): HttpResponse {
    val allowMerging = request.executionContext[MergeContext]?.allowMerging != false

    if (!allowMerging) {
      return delegate.execute(request)
    }

    check(request.method == HttpMethod.Post) {
      "Apollo: MergeInterceptor can only be used with POST requests"
    }

    val buffer = Buffer()
    request.body!!.writeTo(buffer)

    val pendingOperation = buffer.toPendingOperation(request.url)
    if (pendingOperation.document.definitions.firstOrNull { it is GQLOperationDefinition && it.operationType == "query" } == null) {
      // Not a query, don't merge
      return delegate.execute(request)
    }

    mutex.withLock {
      pendingOperations.add(pendingOperation)
    }
    job?.cancel()
    job = scope.launch {
      delay(interval - (startMark.elapsedNow().inWholeMilliseconds % interval) - 1)
      launch {
        executePendingRequests()
      }
    }
    return pendingOperation.deferred.await()
  }

  fun dispatch() {
    job?.cancel()
    scope.launch {
      executePendingRequests()
    }
  }

  private suspend fun executePendingRequests() {
    val pending = mutex.withLock {
      pendingOperations.toList().also { pendingOperations.clear() }
    }
    if (pending.isEmpty()) return

    val mergedDocument = pending.map { it.document }.merge()
    val mergedVariables = pending.fold(emptyMap<String, Any?>()) { acc, pendingOperation -> acc + pendingOperation.variables }

    val url = pending.map { it.url }.distinct().single()

    val body = buildJsonByteString {
      writeObject {
        name("query")
        value(mergedDocument.toUtf8())
        name("variables")
        writeAny(mergedVariables)
      }
    }

    val request = HttpRequest.Builder(HttpMethod.Post, url)
      .body(object : HttpBody {
        override val contentType: String
          get() = "application/json"
        override val contentLength: Long
          get() = body.size.toLong()

        override fun writeTo(bufferedSink: BufferedSink) {
          bufferedSink.write(body)
        }
      })
      .build()

    val response = delegate.execute(request)

    val data = response.body?.readByteString()

    val bufferedResponse = HttpResponse.Builder(response.statusCode)
      .headers(response.headers)
      .apply {
        if (data != null) {
          body(data)
        }
      }
      .build()
    pending.forEach { it.deferred.complete(bufferedResponse) }
  }
}

private fun List<GQLDocument>.merge(): GQLDocument {
  val variables = mutableListOf<GQLVariableDefinition>()
  val selections = mutableListOf<GQLSelection>()
  val fragments = mutableListOf<GQLFragmentDefinition>()

  forEach {
    it.definitions.forEach {
      if (it is GQLOperationDefinition) {
        variables.addAll(it.variableDefinitions)
        selections.addAll(it.selections)
      }
      if (it is GQLFragmentDefinition) {
        fragments.add(it)
      }
    }
  }

  return GQLDocument(
    fragments + GQLOperationDefinition(
        operationType = "query",
        name = "MergedOperation",
        variableDefinitions = variables,
        selections = selections,
        sourceLocation = null,
        directives = emptyList(),
        description = null
      ),
    null
  )
}

@OptIn(ApolloInternal::class)
@Suppress("UNCHECKED_CAST")
private fun Buffer.toPendingOperation(url: String): PendingOperation {
  var str: String? = null
  var variables: Map<String, Any?>? = null

  jsonReader().use { reader ->
    reader.beginObject()
    while (reader.hasNext()) {
      val name = reader.nextName()
      when (name) {
        "query" -> str = reader.nextString()
        "variables" -> variables = reader.readAny() as Map<String, Any?>?
        else -> reader.skipValue()
      }
    }
    reader.endObject()
  }

  check(str != null) {
    "Apollo: Cannot find 'query' in the request body"
  }

  return PendingOperation(str.toGQLDocument(), variables.orEmpty(), url)
}


class MergeContext(val allowMerging: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<MergeContext>
}

class PendingOperation(val document: GQLDocument, val variables: Map<String, Any?>, val url: String) {
  val deferred = CompletableDeferred<HttpResponse>()
}
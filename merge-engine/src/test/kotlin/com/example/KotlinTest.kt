package com.example

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.http.DefaultHttpEngine
import com.apollographql.apollo.network.http.HttpNetworkTransport
import com.apollographql.merge.engine.MergeEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class KotlinTest {
  @Test
  fun test() = runBlocking {
    val apolloClient = ApolloClient.Builder()
      .networkTransport(
        HttpNetworkTransport.Builder()
          .serverUrl("https://confetti-app.dev/graphql")
          .httpEngine(MergeEngine(DefaultHttpEngine(), 50))
          .build()
      )
      .build()

    val response1 = async {
      apolloClient.query(GetConferences1Query()).execute()
    }
    val response2 = async {
      apolloClient.query(GetConferences2Query()).execute()
    }

    println(response1.await().data)
    println(response2.await().data)
  }
}
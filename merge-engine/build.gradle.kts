import com.gradleup.librarian.gradle.Librarian

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.apollographql.apollo")
}

Librarian.module(project)

dependencies {
  implementation(libs.apollo.runtime)
  implementation(libs.apollo.ast)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation(kotlin("test"))
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.3.9")
}

apollo {
  service("service") {
    packageName.set("com.example")
    srcDir("src/test/graphql")
    addTypename.set("ifPolymorphic")
    introspection {
      endpointUrl.set("https://confetti-app.dev/graphql")
      schemaFile.set(file("src/test/graphql/confetti.graphqls"))
    }

    outputDirConnection {
      connectToKotlinSourceSet("test")
    }
  }
}
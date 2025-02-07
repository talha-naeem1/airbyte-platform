import java.util.Properties

plugins {
  id("io.airbyte.gradle.jvm.app")
  id("io.airbyte.gradle.publish")
  id("io.airbyte.gradle.docker")
}

configurations.all {
  exclude(group = "io.micronaut", module = "micronaut-http-server-netty")
  exclude(group = "io.micronaut.openapi")
  exclude(group = "io.micronaut.flyway")
  exclude(group = "io.micronaut.sql")
}

dependencies {
  ksp(platform(libs.micronaut.platform))
  ksp(libs.bundles.micronaut.annotation.processor)

  implementation(platform(libs.micronaut.platform))
  implementation(libs.bundles.micronaut.light)
  implementation(libs.kotlin.logging)
  implementation(libs.micronaut.jackson.databind)
  implementation(libs.slf4j.api)

  implementation(project(":oss:airbyte-api:server-api"))
  implementation(project(":oss:airbyte-api:workload-api"))
  implementation(project(":oss:airbyte-config:config-secrets"))
  implementation(project(":oss:airbyte-commons-worker"))
  implementation(project(":oss:airbyte-featureflag"))
  implementation(project(":oss:airbyte-worker-models"))

  kspTest(platform(libs.micronaut.platform))
  kspTest(libs.bundles.micronaut.annotation.processor)
  kspTest(libs.bundles.micronaut.test.annotation.processor)

  testImplementation(libs.bundles.micronaut.test)
  testImplementation(libs.mockk)
  testImplementation(libs.kotlin.test.runner.junit5)
  testImplementation(libs.bundles.junit)
  testImplementation(libs.assertj.core)
}

val env = Properties().apply {
  load(rootProject.file(".env.dev").inputStream())
}

airbyte {
  application {
    mainClass.set("io.airbyte.initContainer.ApplicationKt")
    defaultJvmArgs = listOf("-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=75.0")
    @Suppress("UNCHECKED_CAST")
    localEnvVars.putAll(env.toMutableMap() as Map<String, String>)
    localEnvVars.putAll(
      mapOf(
        "AIRBYTE_VERSION" to env["VERSION"].toString(),
        "DATA_PLANE_ID" to "local",
        "MICRONAUT_ENVIRONMENTS" to "test"
      )
    )
  }
  docker {
    imageName.set("init-container")
  }
}


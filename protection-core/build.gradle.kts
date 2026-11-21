plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(libs.junit)
}

tasks.test { testLogging { events("failed", "skipped") } }
sourceSets {
    named("test") { resources.srcDirs("../protection/src/main/assets", "parity") }
}
tasks.test { providers.gradleProperty("feeds").orNull?.let { systemProperty("feeds", it) } }

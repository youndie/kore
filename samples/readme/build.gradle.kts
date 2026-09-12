plugins {
    kotlin("jvm")
}

// THE README'S EXAMPLES, COMPILED. Nothing here runs and nothing is published; the module exists so
// that `./gradlew build` fails when a snippet in README.md stops typing.
//
// It is a module of its own rather than a file in `samples/service` for a reason worth keeping: that
// sample is the MEASUREMENT SUBJECT, and it does not depend on `kore-booblik`. Adding a dependency
// to it to make an example compile would quietly change the binary the published numbers were taken
// from — a sample edit that invalidates a table is exactly what B-50 had to pay for once.
//
// JVM only: a signature is a signature on either platform, and the two suites that matter already
// run on all four targets.
kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(project(":kore-core"))
    implementation(project(":kore-ktor"))
    implementation(project(":kore-booblik"))
    implementation(project(":kore-observability"))
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
}

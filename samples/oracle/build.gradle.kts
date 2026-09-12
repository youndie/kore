plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    // B-47 only. See the catalogue for why a driver appears in this repository at all.
    implementation(libs.sqlx4k.postgres)
    implementation(libs.kotlinx.coroutines.core)
}

// NOT wired into `build`, `check` or `assemble`, and that is deliberate. A run takes a minute, needs
// Docker, and drives containers; attaching it to the ordinary gate is how a gate becomes the thing
// people skip. It is invoked by name.
val oracle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the SIGTERM-under-load oracle against a built sample image"
    mainClass.set("io.github.youndie.kore.oracle.OracleKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Same reasoning as `oracle`: invoked by name, never wired into the ordinary gate.
val measure by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Take the three numbers of research-oracle §4 against the built sample images"
    mainClass.set("io.github.youndie.kore.oracle.MeasureMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Invoked by name, never wired into `build` or `check`: it needs a container and it answers the same
// question every time (B-47).
val driverBehaviour by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check that a pool hands out a connection whose far end is gone — research §1.9"
    mainClass.set("io.github.youndie.kore.oracle.DriverBehaviourKt")
    classpath = sourceSets["main"].runtimeClasspath
}

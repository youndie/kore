plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
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

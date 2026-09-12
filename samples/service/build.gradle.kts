import org.gradle.api.file.FileCollection

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Generates the object /version serves. Applied here because the sample is what proves the
    // generated file is an INPUT of the compilation rather than something the build wrote first.
    id("io.github.youndie.kore.build")
}

version = "0.1.0-sample"

kotlin {
    // NOT `explicitApi()`. The library modules declare their surface deliberately; a sample has no
    // consumers and adding `public` to every line of it would be noise pretending to be rigour.

    // The JVM entry point. `application` and the Ktor Gradle plugin do not apply to a multiplatform
    // module — they are `kotlinJvm`-only — so the runnable jar is built by hand below. That is ten
    // lines against a module split, and a split would make assertion A7 compare two programs rather
    // than two builds of one.
    // Declared here rather than inherited: the root convention gives the four published targets to
    // the `kore-*` modules, and this is not one of them. Two targets, because assertion A7 of the
    // oracle compares exactly these two.
    jvm()

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.kore.sample.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kore-core"))
            implementation(project(":kore-ktor"))
            implementation(libs.ktor.server.cio)
        }
    }
}

// THE RUNNABLE JVM ARTEFACT, assembled rather than declared.
//
// One jar with the runtime classpath in it, so the container's entry point is `java -jar` with no
// wrapper script. A wrapper script is how a process stops being PID 1 and stops receiving SIGTERM,
// which is the one thing this sample exists to receive.
val jvmFatJar by tasks.registering(Jar::class) {
    // THE NAME IS PINNED, not composed from the version, and that is a bug fix rather than a
    // simplification. `archiveClassifier` alone produces `service-<version>-all.jar`, so the moment
    // B-26 gave this project a `version` the artefact was renamed — and `Dockerfile`'s
    // `COPY build/libs/service-all.jar` went on finding the file from *before* that change, which was
    // still lying in `build/libs`. The image kept building, from code an hour old, and nothing said
    // so: a missing file fails a COPY loudly, a stale one does not fail at all.
    archiveFileName.set("service-all.jar")
    manifest { attributes["Main-Class"] = "io.github.youndie.kore.sample.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val jvmTarget = kotlin.targets.getByName("jvm")
    val main = jvmTarget.compilations.getByName("main")
    from(main.output.allOutputs)
    dependsOn(main.compileTaskProvider)

    // `.map` rather than `.get()`: resolving a configuration at configuration time breaks the
    // configuration cache, and this build has it on.
    val runtime: FileCollection = main.runtimeDependencyFiles ?: files()
    from(provider { runtime.files.map { file -> if (file.isDirectory) file else zipTree(file) } })

    // Signature files from signed dependencies are not ours to re-sign, and a jar carrying somebody
    // else's signature of a different set of bytes refuses to start.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}

tasks.named("assemble") { dependsOn(jvmFatJar) }

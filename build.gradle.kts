plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// The target set of research D1, in one place rather than repeated in every module.
//
// Named explicitly and never chosen from `os.name`: a build that picks its native target from the
// build machine publishes exactly one native variant — whichever suited that machine — and a
// consumer on the other architecture has nothing to resolve against. That has happened in this
// portfolio before and it is invisible until somebody tries to depend on the artefact.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            jvmToolchain(libs.versions.jvmToolchain.get().toInt())

            // THE PUBLISHED TARGET SET, and it belongs to the library modules only.
            //
            // A sample is an experiment, not an artefact: `samples/service` names the two targets
            // the oracle compares and nothing else, because a metadata jar for a target no
            // experiment runs on is build time spent proving nothing. It declares them itself.
            if (path.startsWith(":kore-")) {
                jvm()

                // The targets that decide the design. A server binary in this portfolio is one of
                // these two.
                linuxX64()
                linuxArm64()

                // A development target, not a deployment one: it is here so the library builds and
                // its tests run on a laptop. It also carries a documented hole — the environment
                // cannot be enumerated there (research §1.5) — and having the target is what makes
                // that hole something a test can assert rather than something a reader has to take
                // on trust.
                macosArm64()
            }

            // `linuxMain` / `macosMain` / `posixMain` come from here rather than from hand-written
            // `dependsOn` edges. The layout the documents name — `Environment.linux.kt` beside
            // `Environment.macos.kt` under a shared `posixMain` — is exactly what the default
            // hierarchy gives.
            applyDefaultHierarchyTemplate()

            sourceSets.commonTest.dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

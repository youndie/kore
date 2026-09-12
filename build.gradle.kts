plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    // The oracle is the one module that is not multiplatform; it drives the others from outside.
    alias(libs.plugins.kotlin.jvm) apply false
}

// THE COORDINATES, and the version comes from a property because CI composes it.
//
// `-PVERSION=…` is the portfolio's existing scheme. The default is a snapshot rather than a number:
// a build that forgets the property should produce something obviously unreleased, not `1.0.0`.
group = "io.github.youndie"
version = (findProperty("VERSION") as String?) ?: "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
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

        // PUBLISHING, on the library modules only — a sample is an experiment and publishing one
        // would put an artefact in a repository that nothing should ever resolve.
        //
        // Configured here rather than by the portfolio's `sborka.publish` convention plugin, and that
        // is a deliberate refusal rather than an oversight. That plugin is fetched in
        // `pluginManagement`, which is evaluated before any settings plugin runs — so a build that
        // used it could not even be *configured* without the portfolio's repository reachable.
        // B-37 accepted that cost for one module's dependencies; paying it for the whole build would
        // make kore unbuildable for the outsiders it is published for.
        if (path.startsWith(":kore-")) {
            apply(plugin = "maven-publish")
            val moduleName = name
            extensions.configure<PublishingExtension> {
                // REGISTERED UNCONDITIONALLY, even with no credentials, and that is the whole point
                // of writing it this way. A sibling in this portfolio once guarded its repository
                // block on the secret being set: with the secret unset the block registered no
                // repository at all, `publishAllPublications…` had nothing to publish to, and the
                // job went **green having done nothing**. A missing credential should fail a PUT
                // loudly, not turn the publish into a no-op.
                repositories {
                    maven {
                        name = "wip"
                        url = uri("https://reposilite.kotlin.website/snapshots")
                        credentials {
                            username = (findProperty("REPOSILITE_USER") as String?).orEmpty()
                            password = (findProperty("REPOSILITE_SECRET") as String?).orEmpty()
                        }
                    }
                }

                publications.withType(MavenPublication::class.java).configureEach {
                    pom.name.set(moduleName)
                    pom.description.set(
                        "kore — ordered shutdown, health probes, typed configuration and build " +
                            "identity for Kotlin server binaries",
                    )
                    pom.url.set("https://github.com/youndie/kore")
                    pom.licenses {
                        license {
                            name.set("MIT")
                            url.set("https://github.com/youndie/kore/blob/main/LICENSE")
                        }
                    }
                    pom.developers {
                        developer {
                            id.set("youndie")
                            name.set("youndie")
                        }
                    }
                    pom.scm {
                        url.set("https://github.com/youndie/kore")
                        connection.set("scm:git:https://github.com/youndie/kore.git")
                    }
                }
            }
        }
    }
}

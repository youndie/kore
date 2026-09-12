rootProject.name = "kore"

pluginManagement {
    // An INCLUDED BUILD: a plugin cannot be applied by the build that compiles it. The portfolio's
    // convention plugins use the same shape, and it is what lets `kore-build` be published on its
    // own later without changing how this build consumes it.
    includeBuild("kore-build")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // The default, stated rather than relied on: a module that declares its own repository resolves
    // against something the rest of the build cannot see, and the difference shows up as a version
    // nobody can explain.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()

        // THE PORTFOLIO'S OWN REPOSITORY, for the three observability agents and nothing else.
        //
        // B-37 decided this: tracy, metrik and katcher are not on Maven Central and are not being
        // moved there for kore's sake. The cost is stated rather than hidden — `kore-observability`
        // is **portfolio-only**, and a consumer outside it cannot resolve that one module. The other
        // three resolve from Maven Central alone, which is what keeps the cost to one module instead
        // of the whole library. `docs/services/kore-library.md` §5 says so, and so does the README.
        //
        // FILTERED, and the filter is about failure isolation rather than speed. An unfiltered
        // repository takes part in resolving EVERY dependency, so the day this host is unreachable
        // Gradle disables it and fails artefacts it never served — naming the victim rather than the
        // cause. That has cost this portfolio a debugging session already.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "portfolio"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
}

// The stage machine, the health registry, the configuration schema. No idea what an HTTP server is,
// which is what lets the property test drive it with no socket anywhere near it.
include(":kore-core")

// The Ktor integration: the probe and version routes, and the wrapper that calls
// `EmbeddedServer.stop` itself rather than through a shutdown hook.
include(":kore-ktor")

// flush-then-close for booblik, one adapter per client (research D5, decided in B-36). Separate so
// that a service with no broker does not resolve a broker client to get an ordered shutdown.
include(":kore-booblik")

// tracy, metrik and katcher in one call. Separate so that a service wanting the ordered shutdown
// does not resolve three agent artefacts to get it.
include(":kore-observability")

// The fixture the library is judged by, not a demonstration: one source, a JVM binary and a native
// one, so that "the two platforms behave alike" is an assertion rather than a hope.
include(":samples:service")

// The load driver and the assertions of research-oracle §2.3. Plain `kotlin("jvm")`: it drives
// containers from outside and is never itself the thing under test, so it has no reason to be
// multiplatform — and every reason not to be, since it has to keep working when the subject does not.
include(":samples:oracle")

// THE README'S SNIPPETS, COMPILED. Nothing here runs and nothing is published — it exists so that
// `./gradlew build` fails when an example in README.md stops typing. A module of its own rather than
// a file in `:samples:service`, because that sample is the measurement subject and does not depend
// on `:kore-booblik`: adding a dependency to it so an example compiles would change the binary the
// published numbers were taken from.
include(":samples:readme")

// NOT HERE YET: `:kore-booblik`. `docs/services/kore-library.md` §2a names it as a `jvm()`-only
// module, on a decision (research D5) whose premise turned out to be gone — `booblik-native` is
// published for linuxX64 and macosArm64. Its target set is B-36, a question, and a module built to
// a superseded decision is worse than a module that is not there yet.

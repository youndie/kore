rootProject.name = "kore"

pluginManagement {
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
    }
}

// ONE repository, and that is a property worth keeping. kore is published for people outside this
// portfolio to use, so everything it resolves has to be resolvable by them; the three observability
// agents are NOT on Maven Central today, which is why `kore-observability` below has no dependency
// on them yet. That is B-37, not an omission.

// The stage machine, the health registry, the configuration schema. No idea what an HTTP server is,
// which is what lets the property test drive it with no socket anywhere near it.
include(":kore-core")

// The Ktor integration: the probe and version routes, and the wrapper that calls
// `EmbeddedServer.stop` itself rather than through a shutdown hook.
include(":kore-ktor")

// tracy, metrik and katcher in one call. Separate so that a service wanting the ordered shutdown
// does not resolve three agent artefacts to get it.
include(":kore-observability")

// NOT HERE YET: `:kore-booblik`. `docs/services/kore-library.md` §2a names it as a `jvm()`-only
// module, on a decision (research D5) whose premise turned out to be gone — `booblik-native` is
// published for linuxX64 and macosArm64. Its target set is B-36, a question, and a module built to
// a superseded decision is worse than a module that is not there yet.

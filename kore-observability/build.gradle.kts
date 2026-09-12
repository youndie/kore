plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
            // A REAL socket for one test: tracy's sender speaks HTTP to an endpoint, and an
            // in-memory test client cannot receive it. The receiver is what proves a flush left the
            // process rather than that a method was called.
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":kore-core"))

            // The agents' plugins are Ktor plugins, so wiring them needs the application. Declared
            // rather than inherited through the agents: a dependency this module compiles against is
            // one it should name.
            implementation(libs.ktor.server.core)

            // THE THREE AGENTS. B-37 decided where they come from: the portfolio's own repository,
            // declared and filtered in `settings.gradle.kts`, rather than Maven Central where all
            // three answer 404.
            //
            // What that costs is this module and only this module. A consumer outside the portfolio
            // can use `kore-core` and `kore-ktor` from Maven Central and cannot resolve this one —
            // stated in the README and in `docs/services/kore-library.md` §5 rather than discovered
            // at resolution time.
            //
            // `implementation`, not `api`: an agent is something kore *calls*, not something it
            // hands back. A consumer that wants to touch an agent directly depends on it directly,
            // and then its version is that consumer's choice rather than kore's.
            // `api` for tracy alone: `KoreObservability.tracy` hands the agent back so a consumer can
            // build loggers, so tracy is part of this module's surface. The other two are called and
            // never returned.
            api(libs.tracy.agent)
            implementation(libs.metrik.agent)
            implementation(libs.katcher.client)
        }
    }
}

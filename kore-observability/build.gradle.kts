plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(project(":kore-core"))
            // NO AGENT DEPENDENCIES YET, and the reason is not that the wiring is unwritten.
            //
            // The tracy, metrik and katcher artefacts are not on Maven Central — checked on
            // 2026-09-11, all three answer 404 there — and are published only to the portfolio's
            // own repository. kore is public and meant to be consumed, so adding that repository
            // here would make the module unbuildable for everyone outside the portfolio, silently,
            // at resolution time. B-37 is the decision; B-28 is the wiring and is blocked on it.
        }
    }
}

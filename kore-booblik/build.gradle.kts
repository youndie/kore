plugins {
    alias(wip.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":kore-core"))
        }

        // ONE CLIENT PER PLATFORM, and nothing shared between them but kore's own contract. The two
        // have different package names and different APIs; a common abstraction over them would be a
        // third API that is wrong about both.
        jvmMain.dependencies {
            implementation(libs.booblik.client)
        }

        nativeMain.dependencies {
            implementation(libs.booblik.native)
        }
    }
}

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

// An INCLUDED BUILD rather than a subproject, because a plugin cannot be applied by the same build
// that compiles it. The portfolio's convention plugins use the same shape, and it is also what lets
// this be published on its own later.
kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") { useJUnitPlatform() }

gradlePlugin {
    plugins {
        create("koreBuildIdentity") {
            id = "io.github.youndie.kore.build"
            implementationClass = "io.github.youndie.kore.gradle.BuildIdentityPlugin"
        }
    }
}

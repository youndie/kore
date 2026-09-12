package io.github.youndie.kore.gradle

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * The one claim the plugin exists to make: **the generated identity is an input of the compilation,
 * not a side effect of the build.**
 *
 * Stated in Gradle's own terms, which is what can be asserted without decompiling anything: after a
 * new commit the compilation is no longer up to date, and after a rebuild that changed nothing it
 * is. The first half is the failure mode — a stale hash and a green build. The second half is what
 * stops `upToDateWhen { false }` on the generator from recompiling the world on every build, and
 * without it the fix for the first half would be paid for on every keystroke.
 *
 * A real Kotlin Multiplatform project because the source-directory wiring is keyed on that plugin;
 * a project without it would assert the wiring of a branch that never ran.
 */
class CompilationInputTest {
    private fun run(directory: File, vararg args: String) {
        val process = ProcessBuilder(*args).directory(directory).start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${args.toList()} did not finish" }
        check(process.exitValue() == 0) { "${args.toList()} failed" }
    }

    private fun commit(directory: File, message: String) {
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-q", "-m", message)
    }

    private fun project(): File {
        val directory = File.createTempFile("kore-testkit", "").let { it.delete(); it.mkdirs(); it }
        File(directory, "settings.gradle.kts").writeText(
            """
            |pluginManagement { repositories { gradlePluginPortal() } }
            |dependencyResolutionManagement { repositories { mavenCentral() } }
            |rootProject.name = "identity"
            """.trimMargin(),
        )
        File(directory, "build.gradle.kts").writeText(
            """
            |plugins {
            |    kotlin("multiplatform") version "$KOTLIN_VERSION"
            |    id("io.github.youndie.kore.build")
            |}
            |kotlin { jvm() }
            |version = "1.0.0"
            """.trimMargin(),
        )
        // The generated object implements BuildIdentity, which lives in kore-core; this project has
        // no such dependency, so it declares the interface itself. What is under test is whether the
        // generated file reaches the compiler, not what it implements.
        File(directory, "src/commonMain/kotlin").apply { mkdirs() }.let {
            File(it, "Shim.kt").writeText(
                """
                |package io.github.youndie.kore.version
                |
                |public interface BuildIdentity {
                |    public val version: String
                |    public val commit: String
                |    public val dirty: Boolean
                |    public val builtAt: String
                |}
                """.trimMargin(),
            )
        }
        run(directory, "git", "init", "-q")
        run(directory, "git", "config", "user.email", "t@example.com")
        run(directory, "git", "config", "user.name", "t")
        commit(directory, "one")
        return directory
    }

    private fun gradle(directory: File) =
        GradleRunner.create()
            .withProjectDir(directory)
            .withPluginClasspath()
            .withArguments("compileKotlinJvm", "--stacktrace")
            .forwardOutput()

    @Test
    fun `a new commit makes the compilation out of date without cleaning`() {
        val directory = project()
        val first = gradle(directory).build()
        assertEquals(
            TaskOutcome.SUCCESS,
            first.task(":compileKotlinJvm")?.outcome,
            "the first build did not compile",
        )
        val generated = File(directory, "build/generated/kore/io/github/youndie/kore/generated/KoreBuildIdentity.kt")
        assertTrue(generated.isFile, "the identity was not generated into the source directory")
        val firstCommit = Regex("""commit: String = "(.+)"""").find(generated.readText())!!.groupValues[1]

        // NOT `clean`: a build that only works after a clean is the bug, not the proof.
        File(directory, "another.txt").writeText("two")
        commit(directory, "two")
        val second = gradle(directory).build()

        val secondCommit = Regex("""commit: String = "(.+)"""").find(generated.readText())!!.groupValues[1]
        assertTrue(
            firstCommit != secondCommit,
            "the generated commit did not change across a commit: $firstCommit",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":compileKotlinJvm")?.outcome,
            "the new commit never reached the compiler — a stale hash in a green build",
        )
    }

    @Test
    fun `a rebuild that changed nothing does not recompile`() {
        val directory = project()
        gradle(directory).build()

        val second = gradle(directory).build()

        // The generator always re-runs; the compilation must not. Otherwise the honest hash is paid
        // for with a full recompilation on every build.
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":generateKoreBuildIdentity")?.outcome,
            "the generator did not re-run, so an amend or a rebase would go unnoticed",
        )
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            second.task(":compileKotlinJvm")?.outcome,
            "an unchanged identity recompiled the project",
        )
    }

    private companion object {
        const val KOTLIN_VERSION = "2.4.10"
    }
}

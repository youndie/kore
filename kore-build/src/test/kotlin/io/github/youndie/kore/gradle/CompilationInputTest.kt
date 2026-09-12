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

    private fun project(jvmOnly: Boolean = false, extra: String = ""): File {
        val directory = File.createTempFile("kore-testkit", "").let { it.delete(); it.mkdirs(); it }
        File(directory, "settings.gradle.kts").writeText(
            """
            |pluginManagement { repositories { gradlePluginPortal() } }
            |dependencyResolutionManagement { repositories { mavenCentral() } }
            |rootProject.name = "identity"
            """.trimMargin(),
        )
        File(directory, "build.gradle.kts").writeText(
            if (jvmOnly) {
                """
                |plugins {
                |    kotlin("jvm") version "$KOTLIN_VERSION"
                |    id("io.github.youndie.kore.build")
                |}
                |version = "1.0.0"
                |$extra
                """.trimMargin()
            } else {
                """
                |plugins {
                |    kotlin("multiplatform") version "$KOTLIN_VERSION"
                |    id("io.github.youndie.kore.build")
                |}
                |kotlin { jvm() }
                |version = "1.0.0"
                |$extra
                """.trimMargin()
            },
        )
        // The generated object implements BuildIdentity, which lives in kore-core; this project has
        // no such dependency, so it declares the interface itself. What is under test is whether the
        // generated file reaches the compiler, not what it implements.
        File(directory, if (jvmOnly) "src/main/kotlin" else "src/commonMain/kotlin").apply { mkdirs() }.let {
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

    private fun gradle(directory: File, task: String = "compileKotlinJvm") =
        GradleRunner.create()
            .withProjectDir(directory)
            .withPluginClasspath()
            .withArguments(task, "--stacktrace")
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

    /**
     * #70. The plugin wired the generated file to `commonMain` only, so on a `kotlin("jvm")` module
     * the task ran, the file appeared, and nothing compiled it — a green build and an unresolvable
     * import.
     *
     * The assertion is a **reference** to the generated object rather than the file's existence: the
     * file existed the whole time the defect was there, which is exactly what made it silent.
     */
    @Test
    fun `a JVM-only module compiles the generated identity`() {
        val directory = project(jvmOnly = true)
        File(directory, "src/main/kotlin/Uses.kt").writeText(
            """
            |import io.github.youndie.kore.generated.KoreBuildIdentity
            |
            |val commit: String = KoreBuildIdentity.commit
            """.trimMargin(),
        )
        commit(directory, "uses")

        val build = gradle(directory, "compileKotlin").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            build.task(":compileKotlin")?.outcome,
            "the generated identity never reached a JVM-only compilation",
        )
    }

    /**
     * #71. `unknown` is the correct degradation and stays; what was missing is a way in for a commit
     * the driver knows and git cannot be asked for — a Docker context without `.git`, a CI job with
     * the sha in a variable, a replicated working tree with neither.
     *
     * The project here **has** a git repository, and the override still wins: that is the claim. A
     * test run without git would pass against a plugin that quietly ignored the property.
     */
    @Test
    fun `a supplied commit wins over the one git would report`() {
        val directory =
            project(
                extra = """
                    |tasks.named<io.github.youndie.kore.gradle.GenerateBuildIdentity>("generateKoreBuildIdentity") {
                    |    commit.set("deadbeef1234")
                    |}
                """.trimMargin(),
            )

        gradle(directory).build()

        val generated = File(directory, "build/generated/kore/io/github/youndie/kore/generated/KoreBuildIdentity.kt")
        val text = generated.readText()
        assertTrue(text.contains("""commit: String = "deadbeef1234""""), "the supplied commit did not win:\n$text")
        assertTrue(text.contains("dirty: Boolean = false"), "a supplied commit must not claim a dirty tree:\n$text")
    }

    /**
     * A plugin that cannot wire the module it was applied to has to say so. Generating the file and
     * staying quiet is #70 with the report removed.
     */
    @Test
    fun `neither Kotlin plugin is a refusal rather than a file nobody compiles`() {
        val directory = File.createTempFile("kore-testkit-bare", "").let { it.delete(); it.mkdirs(); it }
        File(directory, "settings.gradle.kts").writeText("""rootProject.name = "bare"""")
        File(directory, "build.gradle.kts").writeText("""plugins { id("io.github.youndie.kore.build") }""")

        val failure =
            GradleRunner.create()
                .withProjectDir(directory)
                .withPluginClasspath()
                .withArguments("tasks", "--stacktrace")
                .buildAndFail()

        assertTrue(
            failure.output.contains("needs a Kotlin plugin"),
            "the plugin accepted a module it cannot wire:\n${failure.output.takeLast(2000)}",
        )
    }

    private companion object {
        const val KOTLIN_VERSION = "2.4.10"
    }
}

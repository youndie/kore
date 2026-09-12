package io.github.youndie.kore.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The package is `…kore.gradle` and **not** `…kore.build`, which is the obvious name.
 *
 * This repository is replicated to the build machine by a mutagen session that ignores any path
 * component named `build` — because every Gradle module has one. A Kotlin package called `build` is
 * such a component, so the source file was present on the laptop, absent on the build box, and the
 * compile task reported `NO-SOURCE`: a plugin jar with no plugin in it, and an error message about a
 * missing implementation class that says nothing about replication.
 *
 * Generates the object that answers `GET /version`.
 *
 * Kotlin/Native has no resources and no manifest, so the identity has to be **compiled in** — and the
 * generated file is wired in as a source directory of `commonMain`, which makes it an input of the
 * compilation rather than something the build happens to write first. The failure mode of getting
 * that wrong is a stale commit hash and a green build: a value that looks authoritative and is a
 * release behind.
 */
class BuildIdentityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generate =
            project.tasks.register("generateKoreBuildIdentity", GenerateBuildIdentity::class.java)

        // `.configure { }` rather than a trailing lambda on `register`: that overload takes a vararg
        // of constructor arguments, so the lambda is parsed as one of them.
        generate.configure {
            version.set(project.provider { project.version.toString() })
            packageName.set("io.github.youndie.kore.generated")
            outputDirectory.set(project.layout.buildDirectory.dir("generated/kore"))
            projectDirectory.set(project.layout.projectDirectory.asFile.absolutePath)

            // ALWAYS regenerates, and that is the point rather than laziness. Declaring `.git/HEAD`
            // as an input would miss an amend, a rebase, or a dirty working tree going clean.
            // Regenerating costs a few milliseconds writing one small file, and because Gradle
            // snapshots that file's CONTENT rather than its timestamp, the compilation downstream
            // stays UP-TO-DATE when the identity has not actually changed.
            //
            // That last sentence was false when it was first written: `builtAt` was a wall clock, so
            // the content differed on every build and every build recompiled commonMain and
            // everything under it. `builtAtFor` is what makes it true — measured by
            // CompilationInputTest, which failed on exactly this.
            outputs.upToDateWhen { false }
        }

        // Wired by the TASK PROVIDER, not by a path. Gradle infers the dependency from it, so the
        // compilation cannot run before the file exists — which is the difference between an input
        // and a side effect.
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByName("kotlin")
            val sourceSets = kotlin.javaClass.getMethod("getSourceSets").invoke(kotlin)
            val commonMain =
                sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, "commonMain")
            val kotlinSrc = commonMain.javaClass.getMethod("getKotlin").invoke(commonMain)
            kotlinSrc.javaClass.getMethod("srcDir", Any::class.java).invoke(kotlinSrc, generate)
        }
    }
}

abstract class GenerateBuildIdentity : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val projectDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val facts = readGitFacts(File(projectDirectory.get())) { message -> logger.info(message) }
        val out = outputDirectory.get().asFile
        val target = File(out, packageName.get().replace('.', '/'))
        val file = File(target, "KoreBuildIdentity.kt")
        val previous = file.takeIf { it.isFile }?.readText()
        val text =
            renderBuildIdentity(
                packageName.get(),
                version.get(),
                facts,
                builtAtFor(previous, version.get(), facts, now()),
            )

        out.deleteRecursively()
        target.mkdirs()
        file.writeText(text)
    }

    private fun now(): String = java.time.Instant.now().toString().substringBefore('.') + "Z"
}

/** What git could be asked about this working tree. */
data class GitFacts(val commit: String, val dirty: Boolean) {
    companion object {
        /**
         * What a build with no git reports.
         *
         * A word that reads as an absence, deliberately: an invented-looking hash would be worse than
         * admitting the build could not tell, and a library that cannot be built in a container
         * without `.git` cannot be built in half the CI systems there are.
         */
        const val UNKNOWN = "unknown"
    }
}

/**
 * Asks git, and answers [GitFacts.UNKNOWN] when there is nothing to ask.
 *
 * A top-level function rather than a method so it can be tested against a real temporary repository
 * without standing up a Gradle build — the interesting behaviour is entirely in what git says.
 *
 * [executable] and [timeoutSeconds] are parameters because the two ways of *not* getting an answer —
 * git absent, and git not returning — are branches whose failure is an empty commit string baked
 * into a binary, and a test cannot provoke either one against the real `git` on the PATH.
 */
fun readGitFacts(
    directory: File,
    executable: String = "git",
    timeoutSeconds: Long = 10,
    log: (String) -> Unit = {},
): GitFacts {
    fun git(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder(listOf(executable) + args).directory(directory).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                log("git did not answer within ${timeoutSeconds}s; the build identity will say unknown")
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                process.inputStream.bufferedReader().readText().trim()
            }
        } catch (absent: Exception) {
            // Notably when git is not installed at all, which is the normal state of a build
            // container. Reporting `unknown` is the whole point of catching it.
            log("no git available for the build identity: $absent")
            null
        }

    val commit = git("rev-parse", "--short=12", "HEAD") ?: return GitFacts(GitFacts.UNKNOWN, dirty = false)
    // `--porcelain` is empty exactly when the tree is clean. An unknown commit cannot be dirty, which
    // is why this is only reached once a commit was found.
    return GitFacts(commit, dirty = !git("status", "--porcelain").isNullOrBlank())
}

/**
 * The build date to write: [now] normally, and whatever the previous file said when the identity is
 * otherwise identical.
 *
 * Not an optimisation. The generator deliberately runs on every build, so a wall-clock timestamp
 * changes the file on every build, and the file is a source of `commonMain` — every build would
 * recompile commonMain and everything downstream of it, which on Kotlin/Native is the whole binary.
 * Keeping the timestamp when nothing else moved makes the file a function of the git state, and an
 * unchanged git state leaves the compilation alone.
 *
 * What this costs is honesty about "when": on a tree that has not been committed to since the last
 * build, `builtAt` is when the identity was *first* built here rather than now. That tree is already
 * reporting a `-dirty` commit, which says louder than any timestamp that the binary is not a
 * release. A release build sits on a commit it has not built before, so its timestamp is real.
 */
fun builtAtFor(previous: String?, version: String, facts: GitFacts, now: String): String {
    val existing = previous ?: return now
    fun field(name: String, type: String) = Regex("""$name: $type = "?([^"
]+)"?""").find(existing)?.groupValues?.get(1)

    val unchanged =
        field("version", "String") == version &&
            field("commit", "String") == facts.commit &&
            field("dirty", "Boolean") == facts.dirty.toString()
    return if (unchanged) field("builtAt", "String") ?: now else now
}

/** The generated file, as text. Separated so a test can read it without running Gradle. */
fun renderBuildIdentity(packageName: String, version: String, facts: GitFacts, builtAt: String): String =
    """
    |// GENERATED by the io.github.youndie.kore.build plugin. Do not edit.
    |package $packageName
    |
    |import io.github.youndie.kore.version.BuildIdentity
    |
    |public object KoreBuildIdentity : BuildIdentity {
    |    override val version: String = "$version"
    |    override val commit: String = "${facts.commit}"
    |    override val dirty: Boolean = ${facts.dirty}
    |    override val builtAt: String = "$builtAt"
    |}
    |
    """.trimMargin()

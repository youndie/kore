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
 * Generates the object that answers `GET /version`.
 *
 * The package is `…kore.gradle` and **not** `…kore.build`, which is the obvious name: `build` is the
 * directory name every Gradle module has, so tooling that filters build output by path component —
 * sync tools, backup excludes, archive filters — drops a Kotlin package called `build` along with
 * it. The symptom is `compileKotlin NO-SOURCE` and a jar with no plugin class in it, which says
 * nothing about the path that was filtered.
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
        //
        // BOTH KOTLIN PLUGINS, and the second one was missing until #70. A `kotlin("jvm")` module has
        // no `commonMain`, so it never entered the block below, the task still ran, the file still
        // appeared on disk with the right contents — and nothing compiled it. The only symptom was an
        // import that would not resolve in a module where the plugin was applied and apparently
        // working. Silence is what made it worth a report rather than a footnote.
        var wired = false
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            wired = true
            addGeneratedSourceDirectory(project, "commonMain", generate)
        }
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            wired = true
            addGeneratedSourceDirectory(project, "main", generate)
        }

        // A REFUSAL RATHER THAN A FILE NOBODY COMPILES. Applied to a module with neither Kotlin
        // plugin there is nothing to attach the source to, and the old behaviour — generate it
        // anyway, say nothing — is the failure #70 reported one plugin at a time. `afterEvaluate`
        // because "neither was applied" is only knowable once the build script has finished running;
        // the wiring above is not deferred, only this check is.
        project.afterEvaluate {
            check(wired) {
                "the io.github.youndie.kore.build plugin needs a Kotlin plugin to attach the generated " +
                    "identity to: apply org.jetbrains.kotlin.multiplatform or org.jetbrains.kotlin.jvm " +
                    "in ${project.path}, or remove this plugin — it would otherwise write a file that " +
                    "nothing compiles"
            }
        }
    }
}

/**
 * Adds the generator's output as a source directory of [sourceSetName], through reflection.
 *
 * Reflection because `kore-build` must not depend on the Kotlin Gradle plugin's API: the plugin is
 * applied by builds that pin their own Kotlin version, and a compile dependency here would decide
 * that version for them. `sourceSets` and `getByName` are the same shape on the multiplatform and the
 * JVM extension, so one helper serves both — which is also why the JVM case is one line rather than a
 * second implementation.
 */
private fun addGeneratedSourceDirectory(project: Project, sourceSetName: String, generate: Any) {
    val kotlin = project.extensions.getByName("kotlin")
    val sourceSets = kotlin.javaClass.getMethod("getSourceSets").invoke(kotlin)
    val sourceSet =
        sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, sourceSetName)
    val kotlinSrc = sourceSet.javaClass.getMethod("getKotlin").invoke(sourceSet)
    kotlinSrc.javaClass.getMethod("srcDir", Any::class.java).invoke(kotlinSrc, generate)
}

abstract class GenerateBuildIdentity : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val projectDirectory: Property<String>

    /**
     * The commit, when the thing driving the build knows it and git does not — #71.
     *
     * Unset, git is asked and an unreachable one degrades to `unknown`, which stays correct: an
     * invented-looking hash would be worse than admitting the build could not tell. But `unknown` is
     * often a value that **was** available and had no way in — a Docker build context usually
     * excludes `.git`, a CI job has the sha in an environment variable long before it has a checkout,
     * and a replicated working tree has neither.
     *
     * A property rather than a conventional environment variable read inside the plugin: which
     * variable names the commit is the consumer's fact, and a plugin guessing wrong would compile a
     * *different* commit into the binary, which is worse than `unknown`.
     *
     * ```kotlin
     * tasks.named<GenerateBuildIdentity>("generateKoreBuildIdentity") {
     *     commit.set(providers.environmentVariable("GITHUB_SHA"))
     * }
     * ```
     *
     * **Set, it also means `dirty = false`, and that is a claim rather than an omission.** An
     * externally supplied commit is a statement about a commit, not about a working tree; the driver
     * that knows the sha is building *that* sha. Asking git for the dirty half would defeat the point
     * — the case this exists for is one where git cannot be asked at all.
     */
    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val commit: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val facts =
            commit.orNull?.takeIf { it.isNotBlank() }?.let { supplied -> GitFacts(supplied, dirty = false) }
                ?: readGitFacts(File(projectDirectory.get())) { message -> logger.info(message) }
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

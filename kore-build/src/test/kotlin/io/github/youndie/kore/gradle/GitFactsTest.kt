package io.github.youndie.kore.gradle

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against a **real** temporary repository.
 *
 * The interesting behaviour is entirely in what git says, so a double would be testing the double.
 * Creating a repository, committing to it and dirtying it takes four commands and covers every case
 * the plugin has: a commit, a dirty tree, and no repository at all.
 */
class GitFactsTest {
    private fun temp(): File = File.createTempFile("kore", "").let { it.delete(); it.mkdirs(); it }

    private fun run(directory: File, vararg args: String) {
        val process = ProcessBuilder(*args).directory(directory).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "${args.toList()} did not finish" }
        check(process.exitValue() == 0) { "${args.toList()} failed" }
    }

    private fun repository(): File =
        temp().also { directory ->
            run(directory, "git", "init", "-q")
            run(directory, "git", "config", "user.email", "t@example.com")
            run(directory, "git", "config", "user.name", "t")
            File(directory, "a.txt").writeText("one")
            run(directory, "git", "add", "-A")
            run(directory, "git", "commit", "-q", "-m", "one")
        }

    @Test
    fun `a clean repository reports its commit and is not dirty`() {
        val facts = readGitFacts(repository())

        assertEquals(12, facts.commit.length, "the commit was not a short hash: ${facts.commit}")
        assertTrue(!facts.dirty, "a freshly committed tree was reported dirty")
    }

    /**
     * The difference between "this is commit abc1234" and "this is something somebody had on their
     * laptop", which is worth one boolean.
     */
    @Test
    fun `an uncommitted change makes the tree dirty`() {
        val directory = repository()
        File(directory, "a.txt").writeText("two")

        assertTrue(readGitFacts(directory).dirty, "an uncommitted change was not reported")
    }

    @Test
    fun `an untracked file also makes the tree dirty`() {
        val directory = repository()
        File(directory, "b.txt").writeText("new")

        // `--porcelain` lists untracked files too, and a binary built beside a file nobody committed
        // is as unreproducible as one built on a modified file.
        assertTrue(readGitFacts(directory).dirty)
    }

    @Test
    fun `a directory that is not a repository reports unknown`() {
        val facts = readGitFacts(temp())

        assertEquals(GitFacts.UNKNOWN, facts.commit)
        assertTrue(!facts.dirty, "an unknown commit cannot be dirty")
    }

    /**
     * A git that never answers — an index.lock held by something else, a repository on a filesystem
     * that has gone away. Without the timeout the build hangs; with it but without a `null`, the
     * empty output becomes the commit and the binary claims to be built from nothing.
     */
    @Test
    fun `a git that does not answer in time reports unknown`() {
        val directory = repository()
        val slow = File(directory, "slow-git").apply {
            writeText("#!/bin/sh\nsleep 30\n")
            setExecutable(true)
        }

        val facts = readGitFacts(directory, executable = slow.absolutePath, timeoutSeconds = 1)

        assertEquals(GitFacts.UNKNOWN, facts.commit)
    }

    /** The normal state of a build container: no git installed at all. */
    @Test
    fun `a missing git reports unknown rather than failing the build`() {
        val facts = readGitFacts(repository(), executable = "/nonexistent/git")

        assertEquals(GitFacts.UNKNOWN, facts.commit)
        assertTrue(!facts.dirty)
    }

    @Test
    fun `the rendered file compiles to what a reader expects`() {
        val text =
            renderBuildIdentity(
                "io.github.youndie.kore.generated",
                "1.2.3",
                GitFacts("abc123abc123", dirty = true),
                "2026-09-12T00:00:00Z",
            )

        assertTrue(text.contains("""override val commit: String = "abc123abc123""""))
        assertTrue(text.contains("override val dirty: Boolean = true"))
        assertTrue(text.contains("""override val version: String = "1.2.3""""))
        assertTrue(text.startsWith("// GENERATED"), "the file did not say it is generated")
    }
}

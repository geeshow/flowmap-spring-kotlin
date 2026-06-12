package com.flowmap.callgraph

import java.io.File

/**
 * Git access via the `git` CLI (no extra dependency, version-stable). Provides the
 * commit list of a branch/range, per-commit changed files with new-side changed line
 * ranges (`-U0`, rename-aware), and a file's content at a revision. [Impact] combines
 * these with [PsiSourceParser] to attribute changed lines to graph node ids.
 */
object GitLog {

    data class Commit(
        val sha: String, val shortSha: String, val author: String, val email: String,
        val date: String, val subject: String, val parents: List<String>,
    )

    /** A changed file with the NEW-side line ranges touched (empty for pure deletions). */
    data class FileChange(
        val path: String, val oldPath: String?, val changeType: String, val newRanges: List<IntRange>,
    )

    private fun run(repo: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args)
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.readBytes()
        p.waitFor()
        return out
    }

    fun isRepo(repo: File): Boolean =
        run(repo, "rev-parse", "--is-inside-work-tree").trim() == "true"

    /** Pick the default branch: explicit override → origin/HEAD → main/master/develop → current HEAD. */
    fun resolveBranch(repo: File, override: String?): String? {
        if (override != null) return if (verifyRef(repo, override)) override else null
        run(repo, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
            .trim().removePrefix("origin/").takeIf { it.isNotEmpty() }
            ?.let { if (verifyRef(repo, it)) return it }
        for (c in listOf("main", "master", "develop")) if (verifyRef(repo, c)) return c
        return run(repo, "rev-parse", "--abbrev-ref", "HEAD").trim().takeIf { it.isNotEmpty() && it != "HEAD" }
    }

    private fun verifyRef(repo: File, ref: String): Boolean =
        run(repo, "rev-parse", "--verify", "--quiet", ref).trim().isNotEmpty()

    /** Commits newest-first: either [maxCount] on [branch], or an explicit [range] like "A..B". */
    fun commits(repo: File, branch: String, maxCount: Int?, range: String?): List<Commit> {
        val sep = ""
        val fmt = listOf("%H", "%h", "%an", "%ae", "%aI", "%P", "%s").joinToString(sep)
        val args = mutableListOf("log", "--no-color", "--no-merges", "--format=$fmt")
        if (range != null) args.add(range) else { args.add(branch); if (maxCount != null) args.add("-n$maxCount") }
        return run(repo, *args.toTypedArray()).lineSequence().filter { it.isNotBlank() }.map { line ->
            val f = line.split(sep)
            Commit(
                sha = f[0], shortSha = f.getOrElse(1) { "" }, author = f.getOrElse(2) { "" },
                email = f.getOrElse(3) { "" }, date = f.getOrElse(4) { "" }, subject = f.getOrElse(6) { "" },
                parents = f.getOrElse(5) { "" }.split(" ").filter { it.isNotEmpty() },
            )
        }.toList()
    }

    /** Per-file new-side changed line ranges for [sha] vs its first parent. */
    fun changesIn(repo: File, sha: String): List<FileChange> =
        parseDiff(run(repo, "show", sha, "--first-parent", "-U0", "-M", "--no-color", "--format="))

    /** Content of [path] at [sha], or null if absent. */
    fun fileAt(repo: File, sha: String, path: String): String? =
        run(repo, "show", "$sha:$path").ifEmpty { null }

    private val HUNK = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@")

    private fun parseDiff(text: String): List<FileChange> {
        val out = ArrayList<FileChange>()
        var aPath: String? = null
        var bPath: String? = null
        var ctype = "MODIFY"
        var ranges = ArrayList<IntRange>()
        fun flush() {
            val path = bPath ?: aPath ?: return
            out.add(FileChange(path, aPath?.takeIf { it != path }, ctype, ranges))
        }
        for (raw in text.lines()) {
            when {
                raw.startsWith("diff --git ") -> { flush(); aPath = null; bPath = null; ctype = "MODIFY"; ranges = ArrayList() }
                raw.startsWith("rename from ") -> { aPath = raw.removePrefix("rename from "); ctype = "RENAME" }
                raw.startsWith("rename to ") -> bPath = raw.removePrefix("rename to ")
                raw.startsWith("new file") -> ctype = "ADD"
                raw.startsWith("deleted file") -> ctype = "DELETE"
                raw.startsWith("--- a/") -> aPath = raw.removePrefix("--- a/")
                raw.startsWith("+++ b/") -> bPath = raw.removePrefix("+++ b/")
                raw.startsWith("@@") -> HUNK.find(raw)?.let { m ->
                    val start = m.groupValues[1].toInt()
                    val count = m.groupValues[2].toIntOrNull() ?: 1
                    if (count > 0) ranges.add(start until (start + count)) else ranges.add(start..start)
                }
            }
        }
        flush()
        return out.filter { it.path.isNotEmpty() }
    }
}

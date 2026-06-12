package com.flowmap.callgraph

import java.io.File

/**
 * Attach human/API descriptions from Spring REST Docs snippets.
 *
 * REST Docs emits, per documented operation, a directory of `.adoc` snippets
 * (default `build/generated-snippets/<operationId>/`). We read:
 *   * `http-request.adoc`  -> the operation's HTTP method + request path
 *   * `description.adoc`   -> (convention) a one-line description, if present
 * and build a `{(METHOD, normalizedPath) -> description}` map that
 * [GraphBuilder] attaches to the matching controller endpoint node. That node
 * is also the S2S provider in `combine`, so the description flows to s2s edges.
 *
 * Stock snippets carry no description field (they document request/response
 * shape), so `description.adoc` is a convention; absent it, the operationId
 * (folder name) is used so the endpoint is at least labeled. Pure port of the
 * Python tool's restdoc.py.
 */
object RestDocs {

    private val REQ_LINE_RE = Regex("(?m)^\\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(\\S+)")
    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")

    /** Return {(httpMethod, normalizedPath) -> description}. */
    fun load(restdocsDir: String?): Map<Pair<String, String>, String> {
        val out = LinkedHashMap<Pair<String, String>, String>()
        val root = restdocsDir?.let { File(it) } ?: return out
        if (!root.isDirectory) return out
        root.walkTopDown().filter { it.isFile && it.name == "http-request.adoc" }.forEach { req ->
            val (verb, path) = parseRequest(req) ?: return@forEach
            val opDir = req.parentFile
            out[verb to normalize(path)] = readDescription(opDir) ?: opDir.name
        }
        return out
    }

    /** Collapse `{var}` and concrete id segments (numeric / uuid) to `{}` for matching. */
    fun normalize(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        val parts = path.substringBefore("?").split("/")
            .filter { it.isNotEmpty() }
            .map { seg -> if (seg.startsWith("{") || looksLikeId(seg)) "{}" else seg }
        return "/" + parts.joinToString("/")
    }

    private fun looksLikeId(seg: String): Boolean =
        (seg.isNotEmpty() && seg.all { it.isDigit() }) || UUID_RE.matches(seg)

    private fun parseRequest(f: File): Pair<String, String>? {
        val text = try { f.readText() } catch (_: Exception) { return null }
        val m = REQ_LINE_RE.find(text) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun readDescription(opDir: File): String? {
        val f = File(opDir, "description.adoc")
        if (!f.isFile) return null
        return try {
            f.readLines().map { it.trim() }.firstOrNull { s ->
                s.isNotEmpty() && s.first() !in charArrayOf('=', '[', '-', '/')
            }
        } catch (_: Exception) {
            null
        }
    }
}

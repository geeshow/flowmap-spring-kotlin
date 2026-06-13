package com.flowmap.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Lightweight `_manifest.json` writer — a shared contract across the 3 codebases
 * (backend analyzer / ts-analyzer / web). It is an ADDITIVE artifact: existing
 * outputs (`<project>.json`, `<project>.openapi.json`, `_combined.json`,
 * `_openapi.json`) are left untouched.
 *
 * Schema:
 * {
 *   "version": 1,
 *   "generated": "<ISO8601 UTC>",
 *   "projects": [
 *     { "name", "type": "backend", "graph": "<p>.json",
 *       "openapi": "<p>.openapi.json"|null, "impact": "<p>.impact.json"|null,
 *       "join": null, "screens": null, "nodes": N, "edges": M, "generated": "<ISO8601>" }
 *   ]
 * }
 *
 * The project set is decided by a directory scan that mirrors the `combine`
 * input filter: pure `<project>.json` files only (exclude `_*.json` and the
 * `.openapi.json`/`.impact.json`/`.join.json`/`.screens.json` siblings).
 */
object Manifest {
    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
    }

    /** ISO8601 UTC instant, e.g. `2026-06-13T11:00:00Z`. */
    private fun iso(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.atZone(java.time.ZoneOffset.UTC).toInstant())

    /**
     * The same input filter `combine --dir` uses: pure call-graph JSONs only.
     * Excludes `_*.json` (combine output like `_combined.json`) and sibling
     * artifacts (`*.openapi.json`, `*.impact.json`, `*.join.json`, `*.screens.json`).
     */
    private fun projectGraphFiles(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") &&
                !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json") &&
                !f.name.endsWith(".join.json") && !f.name.endsWith(".screens.json")
        }?.sortedBy { it.name } ?: emptyList()

    /** Read `meta.nodes`/`meta.edges`, falling back to the nodes/edges array lengths. */
    private fun nodeEdgeCounts(graphFile: File): Pair<Int, Int> {
        val root = mapper.readTree(graphFile.readText())
        val meta = root["meta"]
        val nodes = meta?.get("nodes")?.takeIf { it.isNumber }?.asInt()
            ?: (root["nodes"]?.takeIf { it.isArray }?.size() ?: 0)
        val edges = meta?.get("edges")?.takeIf { it.isNumber }?.asInt()
            ?: (root["edges"]?.takeIf { it.isArray }?.size() ?: 0)
        return nodes to edges
    }

    /**
     * Build the manifest entries + serialized JSON for [dir], then write
     * `_manifest.json` into it. Returns the number of project entries written.
     */
    fun write(dir: File): Int {
        val projects = projectGraphFiles(dir).map { graphFile ->
            val base = graphFile.name.removeSuffix(".json")
            val openapi = File(dir, "$base.openapi.json").takeIf { it.isFile }?.name
            val impact = File(dir, "$base.impact.json").takeIf { it.isFile }?.name
            val (nodes, edges) = nodeEdgeCounts(graphFile)
            linkedMapOf<String, Any?>(
                "name" to base,
                "type" to "backend",
                "graph" to graphFile.name,
                "openapi" to openapi,
                "impact" to impact,
                "join" to null,
                "screens" to null,
                "nodes" to nodes,
                "edges" to edges,
                "generated" to iso(Instant.ofEpochMilli(graphFile.lastModified())),
            )
        }
        val manifest = linkedMapOf<String, Any?>(
            "version" to 1,
            "generated" to iso(Instant.now()),
            "projects" to projects,
        )
        File(dir, "_manifest.json").writeText(mapper.writeValueAsString(manifest))
        return projects.size
    }
}

package com.flowmap.callgraph

import java.io.File

/**
 * Change-impact analysis: map each commit's changed lines to call-graph node ids and
 * propagate via reverse BFS (callers) to find affected endpoints/services.
 *
 * Join model (the "recent range → current graph" mode): a method is "changed" in a
 * commit when that commit's changed line ranges fall inside the method's range *at that
 * revision* ([PsiSourceParser] on the blob content). The method's node id
 * (`<fqcn>#<method>`) is then joined to the CURRENT analyzed graph. Methods absent from
 * the current graph (renamed/removed/untracked layer) are reported with `inGraph:false`.
 *
 * Outputs (all three requested):
 *  - `commits[]`     per-commit changed nodes + impacted endpoints/services
 *  - `subgraph`      node-link of all changed nodes + their caller chains (web hydrate)
 *  - `endpointImpact[]` aggregate: endpoint → which commits affect it (release notes)
 */
object Impact {

    fun analyze(repo: File, branch: String, commits: List<GitLog.Commit>, graph: CallGraph, depth: Int): Map<String, Any?> {
        val nodeById = graph.nodes.associateBy { it.id }
        val parser = PsiSourceParser()
        val perCommit = ArrayList<Map<String, Any?>>()
        val allChangedInGraph = LinkedHashSet<String>()
        val endpointToCommits = LinkedHashMap<String, LinkedHashSet<String>>()

        try {
            for (c in commits) {
                val changes = GitLog.changesIn(repo, c.sha)
                val changedIds = LinkedHashSet<String>()
                for (ch in changes) {
                    if (ch.changeType == "DELETE" || !ch.path.endsWith(".kt") || ch.newRanges.isEmpty()) continue
                    val text = GitLog.fileAt(repo, c.sha, ch.path) ?: continue
                    for (fn in parser.functions(ch.path, text)) {
                        if (ch.newRanges.any { it.first <= fn.endLine && fn.startLine <= it.last }) changedIds.add(fn.nodeId)
                    }
                }
                val inGraph = changedIds.filter { it in nodeById }
                allChangedInGraph.addAll(inGraph)

                val sub = Bfs.bfs(graph, inGraph, Bfs.Direction.CALLERS, depth)
                val endpoints = sub.nodes.filter { it.endpoint != null || it.httpMethod != null }
                val services = sub.nodes.mapNotNull { it.project ?: it.externalService }.distinct().sorted()
                for (ep in endpoints) endpointToCommits.getOrPut(ep.id) { LinkedHashSet() }.add(c.shortSha)

                perCommit.add(linkedMapOf(
                    "sha" to c.sha, "shortSha" to c.shortSha, "author" to c.author,
                    "date" to c.date, "subject" to c.subject,
                    "changedFiles" to changes.map { it.path },
                    "changedNodes" to changedIds.map { linkedMapOf("id" to it, "inGraph" to (it in nodeById)) },
                    "impactedEndpoints" to endpoints.map { endpointRef(it) },
                    "impactedServices" to services,
                ))
            }
        } finally {
            parser.close()
        }

        val subgraph = Bfs.bfs(graph, allChangedInGraph.toList(), Bfs.Direction.CALLERS, depth)
        val endpointImpact = endpointToCommits.entries.map { (id, shas) ->
            val n = nodeById[id]
            linkedMapOf<String, Any?>(
                "id" to id, "httpMethod" to n?.httpMethod, "endpoint" to n?.endpoint,
                "service" to (n?.project ?: n?.externalService), "description" to n?.description,
                "commits" to shas.toList(),
            )
        }.sortedByDescending { (it["commits"] as List<*>).size }

        return linkedMapOf(
            "branch" to branch,
            "commitCount" to commits.size,
            "depth" to depth,
            "changedNodeCount" to allChangedInGraph.size,
            "commits" to perCommit,
            "subgraph" to subgraph.toNodeLink(linkedMapOf("kind" to "impact-subgraph")),
            "endpointImpact" to endpointImpact,
        )
    }

    private fun endpointRef(n: MethodNode): Map<String, Any?> = linkedMapOf(
        "id" to n.id, "httpMethod" to n.httpMethod, "endpoint" to n.endpoint,
        "service" to (n.project ?: n.externalService), "description" to n.description,
    )
}

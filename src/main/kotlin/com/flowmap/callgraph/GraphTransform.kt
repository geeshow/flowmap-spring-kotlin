package com.flowmap.callgraph

/** Pure post-build [CallGraph] transforms (no Analysis API). */
object GraphTransform {

    /**
     * Keep only public nodes and contract call paths through the dropped ones:
     * `public a -> private b -> public c` becomes `public a -> public c`.
     *
     * A node is dropped iff its `visibility` is not `"public"` (i.e. private /
     * protected / internal declarations). All synthetic nodes — controller
     * endpoints, external clients, resources, gateways, inherited stubs — are
     * emitted with `visibility = "public"`, so only real non-public method
     * declarations are removed.
     *
     * The contracted edge inherits the LANDING hop's mode/kind/relation/callSite
     * (the edge that actually reaches the surviving node), so external / resource
     * / batch / kafka / s2s classification survives the collapse. Paths through
     * dropped nodes that never reach a public node simply disappear, and cycles
     * among dropped nodes terminate via a visited set.
     */
    fun publicOnly(graph: CallGraph): CallGraph {
        val dropped = graph.nodes.asSequence()
            .filter { it.visibility != "public" }
            .map { it.id }
            .toHashSet()
        if (dropped.isEmpty()) return graph

        val outEdges: Map<String, List<CallEdge>> = graph.edges.groupBy { it.source }
        val kept = graph.nodes.filter { it.id !in dropped }

        val newEdges = LinkedHashMap<List<Any?>, CallEdge>()
        for (n in kept) {
            for (e in outEdges[n.id].orEmpty()) {
                if (e.target !in dropped) {
                    newEdges.putIfAbsent(e.key(), e)            // public -> public: keep as-is
                } else {
                    for (land in landings(e.target, dropped, outEdges)) {
                        if (land.target == n.id) continue       // skip self-loops introduced by the collapse
                        val contracted = land.copy(source = n.id)
                        newEdges.putIfAbsent(contracted.key(), contracted)
                    }
                }
            }
        }
        return CallGraph(kept, newEdges.values.toList())
    }

    /**
     * From a dropped node, walk forward through other dropped nodes and collect
     * every edge that lands on a kept node — those landing edges carry the
     * attributes the contracted edge should inherit.
     */
    private fun landings(start: String, dropped: Set<String>, out: Map<String, List<CallEdge>>): List<CallEdge> {
        val result = ArrayList<CallEdge>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(start) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            for (e in out[cur].orEmpty()) {
                if (e.target in dropped) stack.addLast(e.target) else result.add(e)
            }
        }
        return result
    }
}

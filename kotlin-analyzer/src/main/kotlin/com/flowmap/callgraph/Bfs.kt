package com.flowmap.callgraph

import java.util.ArrayDeque

/** BFS over the call graph to extract a method's callers/callees subgraph. */
object Bfs {

    /** Match by exact id, then by exact method name, then by substring. */
    fun findNodes(graph: CallGraph, query: String): List<MethodNode> {
        val q = query.trim()
        graph.nodes.filter { it.id == q }.let { if (it.isNotEmpty()) return it }
        graph.nodes.filter { it.method == q }.let { if (it.isNotEmpty()) return it }
        val ql = q.lowercase()
        return graph.nodes.filter {
            it.id.lowercase().contains(ql) || "${it.fqcn}.${it.method}".lowercase().contains(ql)
        }
    }

    enum class Direction { BOTH, CALLERS, CALLEES }

    fun bfs(graph: CallGraph, roots: List<String>, direction: Direction, depth: Int): CallGraph {
        val outAdj = HashMap<String, MutableList<CallEdge>>()
        val inAdj = HashMap<String, MutableList<CallEdge>>()
        for (e in graph.edges) {
            outAdj.getOrPut(e.source) { mutableListOf() }.add(e)
            inAdj.getOrPut(e.target) { mutableListOf() }.add(e)
        }
        val nodeById = graph.nodes.associateBy { it.id }

        val keptNodes = LinkedHashSet<String>()
        val keptEdges = LinkedHashMap<List<Any?>, CallEdge>()
        val visited = HashMap<String, Int>()
        val dq = ArrayDeque<String>()
        for (r in roots) if (nodeById.containsKey(r)) { visited[r] = 0; keptNodes.add(r); dq.add(r) }

        val followOut = direction == Direction.BOTH || direction == Direction.CALLEES
        val followIn = direction == Direction.BOTH || direction == Direction.CALLERS

        while (dq.isNotEmpty()) {
            val cur = dq.poll()
            val d = visited[cur] ?: continue
            if (d >= depth) continue
            if (followOut) for (e in outAdj[cur].orEmpty()) {
                keptEdges[e.key()] = e
                keptNodes.add(e.target)
                if (e.target !in visited) { visited[e.target] = d + 1; dq.add(e.target) }
            }
            if (followIn) for (e in inAdj[cur].orEmpty()) {
                keptEdges[e.key()] = e
                keptNodes.add(e.source)
                if (e.source !in visited) { visited[e.source] = d + 1; dq.add(e.source) }
            }
        }
        val nodes = keptNodes.mapNotNull { nodeById[it] }
        return CallGraph(nodes, keptEdges.values.toList())
    }
}

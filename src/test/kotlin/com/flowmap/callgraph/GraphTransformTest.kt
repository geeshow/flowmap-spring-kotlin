package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphTransformTest {

    private fun node(id: String, visibility: String, layer: Layer = Layer.SERVICE) = MethodNode(
        id = id, fqcn = id.substringBefore('#'), method = id.substringAfter('#', id),
        layer = layer, visibility = visibility, isAsync = false, returnType = null,
        httpMethod = null, endpoint = null, externalService = null, externalUrl = null,
        file = null, line = null, project = "p", module = null, urlPlaceholder = null, clientPackage = null,
    )

    private fun edge(s: String, t: String, kind: EdgeKind = EdgeKind.INTERNAL, line: Int? = 1) =
        CallEdge(s, t, CallMode.SYNC, kind, "call", null, line)

    private fun edgeSet(g: CallGraph) = g.edges.map { it.source to it.target }.toSet()

    @Test fun `contracts public-private-public into public-public`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"), node("C#b", "private"), node("C#c", "public")),
            edges = listOf(edge("C#a", "C#b", line = 10), edge("C#b", "C#c", line = 20)),
        )
        val out = GraphTransform.publicOnly(g)
        assertEquals(setOf("C#a", "C#c"), out.nodes.map { it.id }.toSet())
        assertEquals(setOf("C#a" to "C#c"), edgeSet(out))
        assertFalse(out.nodes.any { it.id == "C#b" })
    }

    @Test fun `chains through multiple private hops`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"), node("C#b", "private"),
                node("C#c", "internal"), node("C#d", "public")),
            edges = listOf(edge("C#a", "C#b"), edge("C#b", "C#c"), edge("C#c", "C#d")),
        )
        val out = GraphTransform.publicOnly(g)
        assertEquals(setOf("C#a" to "C#d"), edgeSet(out))
    }

    @Test fun `landing edge attributes are preserved (external survives)`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"),
                node("C#b", "private"),
                node("ext:Pay#charge", "public", Layer.EXTERNAL)),
            edges = listOf(edge("C#a", "C#b", line = 1),
                edge("C#b", "ext:Pay#charge", kind = EdgeKind.EXTERNAL, line = 99)),
        )
        val out = GraphTransform.publicOnly(g)
        val e = out.edges.single { it.target == "ext:Pay#charge" }
        assertEquals("C#a", e.source)
        assertEquals(EdgeKind.EXTERNAL, e.kind)   // landing hop's kind kept
        assertEquals(99, e.callSiteLine)          // landing hop's call site kept
    }

    @Test fun `fan-out to several public targets through one private`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"), node("C#b", "private"),
                node("C#c", "public"), node("C#d", "public")),
            edges = listOf(edge("C#a", "C#b"), edge("C#b", "C#c"), edge("C#b", "C#d")),
        )
        val out = GraphTransform.publicOnly(g)
        assertEquals(setOf("C#a" to "C#c", "C#a" to "C#d"), edgeSet(out))
    }

    @Test fun `private cycle with no public exit drops the path`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"), node("C#b", "private"), node("C#c", "private")),
            edges = listOf(edge("C#a", "C#b"), edge("C#b", "C#c"), edge("C#c", "C#b")),
        )
        val out = GraphTransform.publicOnly(g)
        assertEquals(listOf("C#a"), out.nodes.map { it.id })
        assertTrue(out.edges.isEmpty())   // terminates (no infinite loop) and emits nothing
    }

    @Test fun `no private nodes returns graph unchanged`() {
        val g = CallGraph(
            nodes = listOf(node("C#a", "public"), node("C#c", "public")),
            edges = listOf(edge("C#a", "C#c")),
        )
        val out = GraphTransform.publicOnly(g)
        assertEquals(setOf("C#a" to "C#c"), edgeSet(out))
    }
}

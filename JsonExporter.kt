package com.example.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object JsonExporter {
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun write(graph: CallGraph, out: Path) {
        val payload = linkedMapOf(
            "generatedAt" to Instant.now().toString(),
            "nodeCount" to graph.nodes.size,
            "edgeCount" to graph.edges.size,
            "nodes" to graph.nodes,
            "edges" to graph.edges,
        )
        out.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(out).use { mapper.writeValue(it, payload) }
    }
}

package com.example.callgraph

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * Syncs the current call graph into Postgres as a full snapshot replace
 * (TRUNCATE + batch insert inside one transaction). For incremental sync,
 * swap TRUNCATE for an upsert + delete-stale strategy keyed by a run id.
 */
object PostgresSync {

    fun sync(graph: CallGraph, jdbcUrl: String, user: String?, password: String?) {
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.autoCommit = false
            try {
                createSchema(conn)
                conn.createStatement().use { it.execute("TRUNCATE call_edge, method_node") }
                insertNodes(conn, graph.nodes)
                insertEdges(conn, graph.edges)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun createSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS method_node (
                    id          TEXT PRIMARY KEY,
                    fqcn        TEXT NOT NULL,
                    method      TEXT NOT NULL,
                    descriptor  TEXT,
                    layer       TEXT,
                    visibility  TEXT,
                    source_file TEXT,
                    source_path TEXT,
                    line        INT,
                    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS call_edge (
                    from_id        TEXT NOT NULL,
                    to_id          TEXT NOT NULL,
                    call_site_line INT,
                    call_site_file TEXT,
                    kind           TEXT,
                    snapshot_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_call_edge_from ON call_edge(from_id)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_call_edge_to ON call_edge(to_id)")
        }
    }

    private fun insertNodes(conn: Connection, nodes: List<MethodNode>) {
        val sql = """
            INSERT INTO method_node
                (id, fqcn, method, descriptor, layer, visibility, source_file, source_path, line)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            for (n in nodes) {
                ps.setString(1, n.id)
                ps.setString(2, n.fqcn)
                ps.setString(3, n.method)
                ps.setString(4, n.descriptor)
                ps.setString(5, n.layer.name)
                ps.setString(6, n.visibility)
                ps.setString(7, n.sourceFile)
                ps.setString(8, n.sourcePath)
                ps.setObject(9, n.line, Types.INTEGER)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertEdges(conn: Connection, edges: List<CallEdge>) {
        val sql = """
            INSERT INTO call_edge (from_id, to_id, call_site_line, call_site_file, kind)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            for (e in edges) {
                ps.setString(1, e.from)
                ps.setString(2, e.to)
                ps.setObject(3, e.callSiteLine, Types.INTEGER)
                ps.setString(4, e.callSiteFile)
                ps.setString(5, e.kind.name)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }
}

package com.flowmap.callgraph

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Resolves Spring `${a.b.c:default}` placeholders against application*.yml/properties
 * found under each module's resources, plus an optional flat key=value props map.
 *
 * Relaxed binding: `tera.service-url.bank-broker`, `tera.serviceUrl.bankBroker`, and
 * `TERA_SERVICEURL_BANKBROKER` all map to the same canonical key, like Spring Boot.
 */
class YamlPropertyResolver private constructor(
    private val flat: Map<String, String>,
) {
    /** Resolve a placeholder expression to its value, or null if unknown. */
    fun resolve(expr: String?): String? {
        if (expr == null) return null
        val trimmed = expr.trim()
        if (!trimmed.contains("\${")) return trimmed.ifEmpty { null }
        return substitute(trimmed, 0)
    }

    /** Just the placeholder key text inside a single `${...}` (no resolution). */
    fun placeholderKeyOf(expr: String?): String? {
        if (expr == null) return null
        val m = PLACEHOLDER.find(expr) ?: return null
        return m.groupValues[1].substringBefore(":").trim()
    }

    private fun substitute(input: String, depth: Int): String? {
        if (depth > 10) return input
        var anyUnresolved = false
        val out = PLACEHOLDER.replace(input) { mr ->
            val body = mr.groupValues[1]
            val key = body.substringBefore(":").trim()
            val default = if (body.contains(":")) body.substringAfter(":") else null
            val value = lookup(key)
            when {
                value != null -> substitute(value, depth + 1) ?: value
                default != null -> substitute(default, depth + 1) ?: default
                else -> { anyUnresolved = true; mr.value }  // keep "${...}" verbatim
            }
        }
        return if (anyUnresolved && out == input) null else out
    }

    private fun lookup(key: String): String? =
        flat[canonical(key)]

    companion object {
        private val PLACEHOLDER = Regex("""\$\{([^}]*)}""")
        private val SKIP_DIRS = setOf(".git", "build", "out", "target", "node_modules", ".gradle", ".idea")

        /** Build a resolver from a module dir (scans its resources) + extra props. */
        fun forModule(moduleDir: File, profile: String?, extraProps: Map<String, String>): YamlPropertyResolver {
            val flat = LinkedHashMap<String, String>()
            val ymls = collectYmls(moduleDir, profile)
            for (f in ymls) {
                runCatching { loadFlattened(f, flat) }
            }
            extraProps.forEach { (k, v) -> flat[canonical(k)] = v }
            return YamlPropertyResolver(flat)
        }

        fun fromProps(extraProps: Map<String, String>): YamlPropertyResolver {
            val flat = LinkedHashMap<String, String>()
            extraProps.forEach { (k, v) -> flat[canonical(k)] = v }
            return YamlPropertyResolver(flat)
        }

        /** Base application.yml first, then the profile overlay (so it wins). */
        private fun collectYmls(moduleDir: File, profile: String?): List<File> {
            val resourceRoots = ArrayList<File>()
            moduleDir.walkTopDown()
                .onEnter { it.name !in SKIP_DIRS }
                .filter { it.isDirectory && it.name == "resources" && it.path.contains("${File.separator}main${File.separator}") }
                .forEach { resourceRoots.add(it) }
            if (resourceRoots.isEmpty()) resourceRoots.add(moduleDir)

            val ordered = ArrayList<File>()
            for (root in resourceRoots) {
                base(root, "application")?.let { ordered.add(it) }
                if (profile != null) base(root, "application-$profile")?.let { ordered.add(it) }
            }
            return ordered
        }

        private fun base(root: File, name: String): File? =
            listOf("$name.yml", "$name.yaml", "$name.properties")
                .map { File(root, it) }
                .firstOrNull { it.isFile }

        private fun loadFlattened(file: File, into: MutableMap<String, String>) {
            if (file.extension == "properties") {
                file.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@forEach
                    val i = t.indexOf('=')
                    if (i > 0) into[canonical(t.substring(0, i).trim())] = t.substring(i + 1).trim()
                }
                return
            }
            // YAML can hold multiple documents (--- separated); load all.
            val yaml = Yaml()
            file.inputStream().use { input ->
                for (doc in yaml.loadAll(input)) {
                    if (doc is Map<*, *>) flatten("", doc, into)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun flatten(prefix: String, node: Any?, into: MutableMap<String, String>) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) ->
                    val key = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
                    flatten(key, v, into)
                }
                is List<*> -> node.forEachIndexed { idx, v -> flatten("$prefix[$idx]", v, into) }
                null -> {}
                else -> into[canonical(prefix)] = node.toString()
            }
        }

        /** Canonical form: lowercase, strip '-' and '_' so kebab/camel/env all match. */
        internal fun canonical(key: String): String =
            key.lowercase().replace("-", "").replace("_", "")
    }
}

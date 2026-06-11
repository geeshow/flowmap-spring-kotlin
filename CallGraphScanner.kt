package com.example.callgraph

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.name

// ---------- raw, in-memory representation collected from bytecode ----------

private class RawCall(
    val ownerInternal: String,
    val name: String,
    val descriptor: String,
    val line: Int?,
)

private class MethodInfo(
    val access: Int,
    val name: String,
    val descriptor: String,
) {
    var declLine: Int? = null
    val calls = mutableListOf<RawCall>()
}

private class ClassInfo(val internalName: String, val access: Int) {
    val fqcn: String get() = internalName.replace('/', '.')
    var sourceFile: String? = null
    var superName: String? = null
    var interfaces: List<String> = emptyList()
    val annotations = mutableSetOf<String>()
    val methods = mutableListOf<MethodInfo>()
    var layer: Layer = Layer.OTHER
}

// ---------- ASM visitor: parse one class into a ClassInfo ----------

private class CollectingClassVisitor : ClassVisitor(Opcodes.ASM9) {
    lateinit var info: ClassInfo
        private set

    override fun visit(
        version: Int, access: Int, name: String, signature: String?,
        superName: String?, interfaces: Array<out String>?,
    ) {
        info = ClassInfo(name, access).also {
            it.superName = superName
            it.interfaces = interfaces?.toList() ?: emptyList()
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        info.sourceFile = source
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        info.annotations.add(descriptor)
        return null
    }

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<out String>?,
    ): MethodVisitor {
        val mi = MethodInfo(access, name, descriptor)
        info.methods.add(mi)
        return object : MethodVisitor(Opcodes.ASM9) {
            private var currentLine: Int? = null
            override fun visitLineNumber(line: Int, start: Label?) {
                if (mi.declLine == null) mi.declLine = line
                currentLine = line
            }
            override fun visitMethodInsn(
                opcode: Int, owner: String, mName: String, mDesc: String, isInterface: Boolean,
            ) {
                mi.calls.add(RawCall(owner, mName, mDesc, currentLine))
            }
        }
    }
}

// ---------- maps a class+source-file name to a real path on disk ----------

class SourceIndex(roots: List<Path>) {
    private val byRelative = HashMap<String, String>()
    private val byName = HashMap<String, MutableList<String>>()

    init {
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { val s = it.toString(); s.endsWith(".kt") || s.endsWith(".java") }
                    .forEach { p ->
                        val rel = root.relativize(p).toString().replace('\\', '/')
                        byRelative[rel] = p.toString()
                        byName.getOrPut(p.name) { mutableListOf() }.add(p.toString())
                    }
            }
        }
    }

    fun resolve(internalName: String, sourceFile: String?): String? {
        if (sourceFile == null) return null
        val pkg = internalName.substringBeforeLast('/', "")
        val rel = if (pkg.isEmpty()) sourceFile else "$pkg/$sourceFile"
        byRelative[rel]?.let { return it }
        byName[sourceFile]?.let { if (it.size == 1) return it[0] }
        return rel // best-effort relative path when source root isn't provided
    }
}

// ---------- the scanner ----------

class CallGraphScanner(
    private val config: ScanConfig,
    private val sourceIndex: SourceIndex,
) {
    fun scan(classPaths: List<Path>): CallGraph {
        val registry = LinkedHashMap<String, ClassInfo>()
        classPaths.forEach { collectFrom(it, registry) }
        registry.values.forEach { it.layer = resolveLayer(it) }
        return build(registry)
    }

    private fun collectFrom(path: Path, registry: MutableMap<String, ClassInfo>) {
        when {
            Files.isDirectory(path) -> Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                    .forEach { readClass(Files.readAllBytes(it), registry) }
            }
            path.toString().endsWith(".jar") -> JarFile(path.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { e -> jar.getInputStream(e).use { readClass(it.readBytes(), registry) } }
            }
            path.toString().endsWith(".class") -> readClass(Files.readAllBytes(path), registry)
        }
    }

    private fun readClass(bytes: ByteArray, registry: MutableMap<String, ClassInfo>) {
        val cv = CollectingClassVisitor()
        // flags = 0 keeps debug info (line numbers); do NOT use SKIP_DEBUG.
        ClassReader(bytes).accept(cv, 0)
        registry[cv.info.internalName] = cv.info
    }

    private fun resolveLayer(ci: ClassInfo): Layer {
        ci.annotations.firstNotNullOfOrNull { SpringSignatures.layerAnnotations[it] }?.let { return it }
        if (ci.interfaces.any { it in SpringSignatures.repositoryBaseTypes }) return Layer.REPOSITORY
        return Layer.OTHER
    }

    // --- method visibility / synthetic filtering ---

    private fun visibilityOf(access: Int): String = when {
        access and Opcodes.ACC_PUBLIC != 0 -> "public"
        access and Opcodes.ACC_PROTECTED != 0 -> "protected"
        access and Opcodes.ACC_PRIVATE != 0 -> "private"
        else -> "package-private"
    }

    /** Public, non-synthetic business methods only. Kotlin synthetic members are dropped. */
    private fun isIncludable(mi: MethodInfo): Boolean {
        if (mi.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return false
        if (mi.access and Opcodes.ACC_PUBLIC == 0) return false
        if (mi.name == "<init>" || mi.name == "<clinit>") return false
        if (mi.name.contains('$')) return false              // $default, access$, $lambda, ...
        if (mi.name.matches(Regex("component\\d+"))) return false
        return true
    }

    private fun isExternal(owner: String): Boolean =
        config.externalPrefixes.any { owner.startsWith(it) }

    /** Resolve a method up the superclass chain (interfaces handled separately). */
    private fun findMethod(
        registry: Map<String, ClassInfo>, ownerInternal: String, name: String, descriptor: String,
    ): MethodInfo? {
        var current: String? = ownerInternal
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            val ci = registry[current] ?: return null
            ci.methods.firstOrNull { it.name == name && it.descriptor == descriptor }?.let { return it }
            current = ci.superName
        }
        return null
    }

    private fun build(registry: Map<String, ClassInfo>): CallGraph {
        val nodes = LinkedHashMap<String, MethodNode>()
        val edges = mutableListOf<CallEdge>()

        fun idOf(fqcn: String, name: String, desc: String) = "$fqcn#$name$desc"

        // 1) nodes for every public business method of a tracked class
        for (ci in registry.values) {
            if (ci.layer !in config.trackedLayers) continue
            for (mi in ci.methods) {
                if (!isIncludable(mi)) continue
                val id = idOf(ci.fqcn, mi.name, mi.descriptor)
                nodes[id] = MethodNode(
                    id = id, fqcn = ci.fqcn, method = mi.name, descriptor = mi.descriptor,
                    layer = ci.layer, visibility = visibilityOf(mi.access),
                    sourceFile = ci.sourceFile,
                    sourcePath = sourceIndex.resolve(ci.internalName, ci.sourceFile),
                    line = mi.declLine,
                )
            }
        }

        // 2) edges from each tracked method's call sites
        for (ci in registry.values) {
            if (ci.layer !in config.trackedLayers) continue
            for (mi in ci.methods) {
                if (!isIncludable(mi)) continue
                val fromId = idOf(ci.fqcn, mi.name, mi.descriptor)

                for (call in mi.calls) {
                    val callee = registry[call.ownerInternal]
                    when {
                        callee != null && callee.layer in config.trackedLayers -> {
                            val resolved = findMethod(registry, call.ownerInternal, call.name, call.descriptor)
                            // Spring Data repo interfaces inherit save()/findById() etc. -> treat as public API
                            val publicOk = if (resolved != null) isIncludable(resolved)
                                           else callee.layer == Layer.REPOSITORY
                            if (publicOk) {
                                val toId = idOf(callee.fqcn, call.name, call.descriptor)
                                nodes.putIfAbsent(toId, MethodNode(
                                    id = toId, fqcn = callee.fqcn, method = call.name, descriptor = call.descriptor,
                                    layer = callee.layer, visibility = "public",
                                    sourceFile = callee.sourceFile,
                                    sourcePath = sourceIndex.resolve(callee.internalName, callee.sourceFile),
                                    line = resolved?.declLine,
                                ))
                                edges.add(CallEdge(fromId, toId, call.line, ci.sourceFile, EdgeKind.INTERNAL))
                            }
                        }
                        isExternal(call.ownerInternal) -> {
                            val extFqcn = call.ownerInternal.replace('/', '.')
                            val toId = "ext:$extFqcn#${call.name}${call.descriptor}"
                            nodes.putIfAbsent(toId, MethodNode(
                                id = toId, fqcn = extFqcn, method = call.name, descriptor = call.descriptor,
                                layer = Layer.EXTERNAL, visibility = "public",
                                sourceFile = null, sourcePath = null, line = null,
                            ))
                            edges.add(CallEdge(fromId, toId, call.line, ci.sourceFile, EdgeKind.EXTERNAL))
                        }
                    }
                }
            }
        }

        return CallGraph(nodes.values.toList(), edges.distinct())
    }
}

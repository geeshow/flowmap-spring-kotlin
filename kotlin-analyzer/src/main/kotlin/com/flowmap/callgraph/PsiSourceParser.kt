package com.flowmap.callgraph

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Lightweight PSI parser (no BindingContext) that maps Kotlin source *text* to its
 * declared methods and their 1-based line ranges, keyed by the SAME node id the call
 * graph uses (`<fqcn>#<method>`, synthetic `.Companion` stripped — mirrors
 * GraphBuilder.normalizeFqcn). [Impact] feeds it the content of a changed file *at a
 * given revision*, so a commit's changed line ranges map onto graph node ids without
 * re-running full semantic analysis. Cheap enough to call per changed file per commit.
 */
class PsiSourceParser : AutoCloseable {
    private val disposable = Disposer.newDisposable("psi-source-parser")
    private val env: KotlinCoreEnvironment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "psi")
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val factory = KtPsiFactory(env.project)

    data class FnRange(val nodeId: String, val startLine: Int, val endLine: Int)

    /** Declared functions (inside classes/objects) with 1-based inclusive line ranges. */
    fun functions(fileName: String, text: String): List<FnRange> {
        val name = if (fileName.endsWith(".kt")) fileName.substringAfterLast('/') else "Snippet.kt"
        val kt = try { factory.createFile(name, text) } catch (_: Throwable) { return emptyList() }
        val lineIndex = LineIndex(text)
        val out = ArrayList<FnRange>()
        kt.collectDescendantsOfType<KtClassOrObject>().forEach { cls ->
            val fqcn = (cls.fqName?.asString() ?: cls.name ?: return@forEach).removeSuffix(".Companion")
            cls.declarations.filterIsInstance<KtNamedFunction>().forEach { fn ->
                val fnName = fn.name ?: return@forEach
                val r = fn.textRange ?: return@forEach
                out.add(FnRange("$fqcn#$fnName", lineIndex.lineAt(r.startOffset), lineIndex.lineAt(r.endOffset)))
            }
        }
        return out
    }

    override fun close() = Disposer.dispose(disposable)

    /** Precomputed newline offsets → O(log n) offset-to-(1-based)-line. */
    private class LineIndex(text: String) {
        private val newlines = buildList { for (i in text.indices) if (text[i] == '\n') add(i) }
        fun lineAt(offset: Int): Int {
            var lo = 0; var hi = newlines.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (newlines[mid] < offset) lo = mid + 1 else hi = mid }
            return lo + 1
        }
    }
}

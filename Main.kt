package com.example.callgraph

import java.nio.file.Path
import kotlin.system.exitProcess

private class Args(argv: Array<String>) {
    val classes = mutableListOf<Path>()
    val sources = mutableListOf<Path>()
    var out: Path = Path.of("callgraph.json")
    var db: String? = null
    var dbUser: String? = null
    var dbPass: String? = null
    var includeOther = false

    init {
        var i = 0
        while (i < argv.size) {
            when (argv[i]) {
                "--classes" -> classes.add(Path.of(argv[++i]))
                "--sources" -> sources.add(Path.of(argv[++i]))
                "--out" -> out = Path.of(argv[++i])
                "--db" -> db = argv[++i]
                "--db-user" -> dbUser = argv[++i]
                "--db-pass" -> dbPass = argv[++i]
                "--include-other" -> includeOther = true
                "-h", "--help" -> { printUsage(); exitProcess(0) }
                else -> { System.err.println("Unknown arg: ${argv[i]}"); printUsage(); exitProcess(2) }
            }
            i++
        }
    }
}

private fun printUsage() {
    println(
        """
        Usage: callgraph-sync --classes <dir|jar> [--classes ...] [options]

          --classes <path>   compiled classes dir or jar (repeatable, required)
          --sources <path>   source root for file-path resolution (repeatable)
          --out <file>       JSON output file (default: callgraph.json)
          --db <jdbcUrl>     also sync to Postgres, e.g. jdbc:postgresql://localhost:5432/cg
          --db-user <user>
          --db-pass <pass>
          --include-other    also track non-annotated (OTHER) classes
        """.trimIndent(),
    )
}

fun main(argv: Array<String>) {
    val args = Args(argv)
    if (args.classes.isEmpty()) {
        System.err.println("error: at least one --classes is required")
        printUsage()
        exitProcess(2)
    }

    val tracked = buildSet {
        add(Layer.CONTROLLER); add(Layer.SERVICE); add(Layer.REPOSITORY); add(Layer.COMPONENT)
        if (args.includeOther) add(Layer.OTHER)
    }
    val config = ScanConfig(trackedLayers = tracked)

    val scanner = CallGraphScanner(config, SourceIndex(args.sources))
    val graph = scanner.scan(args.classes)

    JsonExporter.write(graph, args.out)
    println("Wrote ${graph.nodes.size} nodes / ${graph.edges.size} edges -> ${args.out}")

    args.db?.let { url ->
        PostgresSync.sync(graph, url, args.dbUser, args.dbPass)
        println("Synced to $url")
    }
}

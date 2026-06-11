package com.example.callgraph

/** Architectural layer a method belongs to. */
enum class Layer { CONTROLLER, SERVICE, REPOSITORY, COMPONENT, EXTERNAL, OTHER }

/** Whether a call edge stays inside the codebase or crosses to an external client. */
enum class EdgeKind { INTERNAL, EXTERNAL }

/** A public method participating in the call graph, with its source location. */
data class MethodNode(
    val id: String,            // "<fqcn>#<method><descriptor>"
    val fqcn: String,
    val method: String,
    val descriptor: String,
    val layer: Layer,
    val visibility: String,
    val sourceFile: String?,   // e.g. "OrderService.kt"
    val sourcePath: String?,   // resolved path under a source root, if available
    val line: Int?,            // method declaration line
)

/** A call from one method to another, carrying the exact call-site location. */
data class CallEdge(
    val from: String,
    val to: String,
    val callSiteLine: Int?,    // line in the CALLER where the invocation happens
    val callSiteFile: String?, // source file of the caller
    val kind: EdgeKind,
)

data class CallGraph(
    val nodes: List<MethodNode>,
    val edges: List<CallEdge>,
)

/** What counts as a layer or an external call. Edit freely for your stack. */
object SpringSignatures {

    /** Class-level annotation descriptors -> layer. */
    val layerAnnotations: Map<String, Layer> = mapOf(
        "Lorg/springframework/web/bind/annotation/RestController;" to Layer.CONTROLLER,
        "Lorg/springframework/stereotype/Controller;" to Layer.CONTROLLER,
        "Lorg/springframework/stereotype/Service;" to Layer.SERVICE,
        "Lorg/springframework/stereotype/Repository;" to Layer.REPOSITORY,
        "Lorg/springframework/stereotype/Component;" to Layer.COMPONENT,
    )

    /** Spring Data base interfaces: a user interface extending one of these is a repository. */
    val repositoryBaseTypes: Set<String> = setOf(
        "org/springframework/data/repository/Repository",
        "org/springframework/data/repository/CrudRepository",
        "org/springframework/data/repository/PagingAndSortingRepository",
        "org/springframework/data/repository/ListCrudRepository",
        "org/springframework/data/jpa/repository/JpaRepository",
        "org/springframework/data/repository/reactive/ReactiveCrudRepository",
    )

    /** Internal-name prefixes treated as "external calls" (HTTP clients, JDBC, Feign, ...). */
    val defaultExternalPrefixes: List<String> = listOf(
        "org/springframework/web/client/RestTemplate",
        "org/springframework/web/reactive/function/client/WebClient",
        "org/springframework/jdbc/core/JdbcTemplate",
        "org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate",
        "feign/",
        "org/springframework/cloud/openfeign/",
    )
}

data class ScanConfig(
    val trackedLayers: Set<Layer> = setOf(
        Layer.CONTROLLER, Layer.SERVICE, Layer.REPOSITORY, Layer.COMPONENT,
    ),
    val externalPrefixes: List<String> = SpringSignatures.defaultExternalPrefixes,
)

"""Heuristics that map source constructs to layers, external calls, async, and batch.

Edit these tables to match your stack (e.g. KakaoPay's in-house HTTP client or
gRPC stub packages can be added to ``EXTERNAL_SIMPLE_TYPES`` / ``EXTERNAL_PREFIXES``).
"""
from __future__ import annotations

from .model import Layer

# Class-level annotations -> architectural layer.
LAYER_ANNOTATIONS: dict[str, Layer] = {
    "RestController": Layer.CONTROLLER,
    "Controller": Layer.CONTROLLER,
    "Service": Layer.SERVICE,
    "Repository": Layer.REPOSITORY,
    "Component": Layer.COMPONENT,
    "Configuration": Layer.CONFIG,
}

# Spring Data base interfaces: a user interface extending one of these is a repository.
REPOSITORY_BASE_TYPES: set[str] = {
    "Repository",
    "CrudRepository",
    "PagingAndSortingRepository",
    "ListCrudRepository",
    "JpaRepository",
    "ReactiveCrudRepository",
    "CoroutineCrudRepository",
}

# Spring Data methods inherited by repository interfaces (not declared in source).
REPOSITORY_INHERITED_METHODS: set[str] = {
    "save", "saveAll", "saveAndFlush", "findById", "findAll", "findAllById",
    "getById", "getReferenceById", "existsById", "count", "delete", "deleteById",
    "deleteAll", "deleteAllById", "flush",
}

# Simple type names treated as external clients when used as a call receiver.
EXTERNAL_SIMPLE_TYPES: set[str] = {
    "RestTemplate", "WebClient", "RestClient", "JdbcTemplate",
    "NamedParameterJdbcTemplate", "KafkaTemplate", "WebTestClient",
}

# Internal-name prefixes (by import FQCN) treated as external calls.
EXTERNAL_PREFIXES: tuple[str, ...] = (
    "org.springframework.web.client.RestTemplate",
    "org.springframework.web.reactive.function.client.WebClient",
    "org.springframework.web.client.RestClient",
    "org.springframework.jdbc.core.JdbcTemplate",
    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
    "org.springframework.kafka.core.KafkaTemplate",
    "feign.",
    "org.springframework.cloud.openfeign.",
)

# Annotation that marks a Feign client interface -> every method call on it is external.
FEIGN_CLIENT_ANNOTATIONS: set[str] = {"FeignClient"}

# ---- async detection -------------------------------------------------------

# Method-level annotations that make a method asynchronous.
ASYNC_ANNOTATIONS: set[str] = {"Async", "Scheduled"}

# Kotlin coroutine builders / CompletableFuture builders that open an async context.
ASYNC_BUILDERS: set[str] = {
    "launch", "async", "withContext", "runBlocking", "supervisorScope",
    "coroutineScope", "flow", "produce",
    "supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync",
}

# Return types that imply an asynchronous/reactive method.
ASYNC_RETURN_TYPES: set[str] = {
    "Mono", "Flux", "CompletableFuture", "Future", "Deferred", "Flow", "Publisher",
}

# ---- Spring Batch ----------------------------------------------------------

BATCH_ENABLE_ANNOTATIONS: set[str] = {"EnableBatchProcessing"}

# Base types / annotations marking a class as a batch component.
BATCH_COMPONENT_SUPERTYPES: set[str] = {
    "Tasklet", "ItemReader", "ItemProcessor", "ItemWriter",
    "ItemStreamReader", "ItemStreamWriter", "StepExecutionListener",
    "JobExecutionListener", "ChunkListener",
}

# Builder methods inside a @Bean that wire batch structure, mapped to a relation tag.
# argument identifier -> referenced bean (another @Bean fun in the same config).
BATCH_WIRING_METHODS: dict[str, str] = {
    "start": "batch:step",
    "next": "batch:step",
    "flow": "batch:step",
    "tasklet": "batch:tasklet",
    "reader": "batch:reader",
    "processor": "batch:processor",
    "writer": "batch:writer",
    "listener": "batch:listener",
}

# Keywords that look like calls (``if (...)``) but are not method invocations.
CALL_KEYWORD_BLACKLIST: set[str] = {
    "if", "while", "for", "when", "catch", "switch", "return", "synchronized",
    "fun", "class", "object", "interface", "super", "this", "do", "else",
    "throw", "try", "is", "as", "in", "by", "where", "init", "constructor",
}

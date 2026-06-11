"""call-graph: static call/called-by graph extractor for Spring Kotlin/Java projects.

Pure-Python, no external dependencies. Parses source under ``.repo/<project>/<module>``
and produces a node-link graph (nodes[] + edges[]) with code locations, sync/async
classification, and Spring Batch (Job -> Step -> Reader/Processor/Writer) relations.
"""

__version__ = "0.1.0"

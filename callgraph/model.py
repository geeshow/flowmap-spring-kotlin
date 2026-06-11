"""Core data model for the call graph.

Mirrors the shape of the original Kotlin/ASM tool, but extended with:
  * sync/async edge mode
  * batch relations (job/step/reader/processor/writer/tasklet)
  * project/module provenance on every node
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class Layer(str, Enum):
    CONTROLLER = "CONTROLLER"
    SERVICE = "SERVICE"
    REPOSITORY = "REPOSITORY"
    COMPONENT = "COMPONENT"
    CONFIG = "CONFIG"
    BATCH = "BATCH"          # Spring Batch Job/Step/Tasklet/Reader/Processor/Writer
    EXTERNAL = "EXTERNAL"    # HTTP/JDBC/Feign/Kafka client crossing the boundary
    OTHER = "OTHER"


class EdgeKind(str, Enum):
    INTERNAL = "internal"    # call resolved to another node in the codebase
    EXTERNAL = "external"    # call into an external client (RestTemplate, WebClient, ...)
    BATCH = "batch"          # structural batch wiring (job -> step -> reader ...)


class CallMode(str, Enum):
    SYNC = "sync"
    ASYNC = "async"


@dataclass(frozen=True)
class MethodNode:
    id: str                       # "<fqcn>#<method>"
    fqcn: str
    method: str
    layer: Layer
    visibility: str               # public / private / protected / internal
    is_async: bool                # suspend / @Async / reactive return
    return_type: Optional[str]
    file: Optional[str]           # path relative to repo root
    line: Optional[int]           # declaration line
    project: Optional[str] = None
    module: Optional[str] = None
    http_method: Optional[str] = None   # GET/POST/... for controller endpoints
    endpoint: Optional[str] = None      # full URL path (class base + method path)
    external_service: Optional[str] = None  # Feign client name / external client type
    external_url: Optional[str] = None       # full external URL (base + path), when known

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "fqcn": self.fqcn,
            "method": self.method,
            "layer": self.layer.value,
            "visibility": self.visibility,
            "async": self.is_async,
            "returnType": self.return_type,
            "httpMethod": self.http_method,
            "endpoint": self.endpoint,
            "externalService": self.external_service,
            "externalUrl": self.external_url,
            "file": self.file,
            "line": self.line,
            "project": self.project,
            "module": self.module,
        }


@dataclass(frozen=True)
class CallEdge:
    source: str                   # caller node id   (node-link uses "source"/"target")
    target: str                   # callee node id
    mode: CallMode                # sync / async
    kind: EdgeKind                # internal / external / batch
    relation: str                 # "call" | "batch:step" | "batch:reader" | ...
    call_site_file: Optional[str]
    call_site_line: Optional[int]

    def key(self) -> tuple:
        return (self.source, self.target, self.relation, self.call_site_line)

    def to_json(self) -> dict:
        return {
            "source": self.source,
            "target": self.target,
            "mode": self.mode.value,
            "kind": self.kind.value,
            "relation": self.relation,
            "callSiteFile": self.call_site_file,
            "callSiteLine": self.call_site_line,
        }


@dataclass
class CallGraph:
    nodes: list[MethodNode] = field(default_factory=list)
    edges: list[CallEdge] = field(default_factory=list)

    def to_node_link(self, meta: Optional[dict] = None) -> dict:
        return {
            "directed": True,
            "multigraph": True,
            "meta": meta or {},
            "nodes": [n.to_json() for n in self.nodes],
            "edges": [e.to_json() for e in self.edges],
        }

"""Resolve parsed sources into a CallGraph (nodes + edges).

Call resolution is type-directed and heuristic:
  * a receiver variable is resolved to a type via local vars, params, constructor
    DI fields, or class fields (the classic Spring constructor-injection pattern);
  * that type is resolved to an FQCN via imports / same package / declared types;
  * bare calls resolve against the caller's own class.
Spring Data inherited methods (save/findById/...) and Spring Batch wiring
(job -> step -> reader/processor/writer/tasklet) are linked structurally.
"""
from __future__ import annotations

import re
from typing import Optional

from . import classify as C
from .model import CallEdge, CallGraph, CallMode, EdgeKind, Layer, MethodNode
from .sourceparse import ParsedFile, ParsedFunction, ParsedType

_BATCH_WIRE_RE = re.compile(
    r"\.(" + "|".join(C.BATCH_WIRING_METHODS) + r")\s*\(\s*(\w+)")
_BATCH_RETURN_TYPES = {"Job", "Step", "Flow", "Tasklet",
                       "ItemReader", "ItemProcessor", "ItemWriter"}


class GraphBuilder:
    def __init__(self, files: list[ParsedFile], *, include_other: bool = False):
        self.files = files
        self.include_other = include_other
        self.types_by_fqcn: dict[str, tuple[ParsedType, ParsedFile]] = {}
        self.types_by_simple: dict[str, list[str]] = {}
        self._index()

    # ---- indexing & classification ----

    def _fqcn(self, pf: ParsedFile, t: ParsedType) -> str:
        return f"{pf.package}.{t.simple_name}" if pf.package else t.simple_name

    def _index(self) -> None:
        for pf in self.files:
            for t in pf.types:
                fq = self._fqcn(pf, t)
                self.types_by_fqcn[fq] = (t, pf)
                self.types_by_simple.setdefault(t.simple_name, []).append(fq)

    def _layer_of(self, t: ParsedType) -> Layer:
        for ann in t.annotations:
            if ann in C.LAYER_ANNOTATIONS:
                lay = C.LAYER_ANNOTATIONS[ann]
                if lay == Layer.CONFIG and any(a in C.BATCH_ENABLE_ANNOTATIONS
                                               for a in t.annotations):
                    return Layer.CONFIG
                return lay
        if any(s in C.REPOSITORY_BASE_TYPES for s in t.supertypes):
            return Layer.REPOSITORY
        if any(s in C.BATCH_COMPONENT_SUPERTYPES for s in t.supertypes):
            return Layer.BATCH
        return Layer.OTHER

    def _is_feign(self, t: ParsedType) -> bool:
        return any(a in C.FEIGN_CLIENT_ANNOTATIONS for a in t.annotations)

    def _is_async_fn(self, fn: ParsedFunction) -> bool:
        if "suspend" in fn.modifiers:
            return True
        if any(a in C.ASYNC_ANNOTATIONS for a in fn.annotations):
            return True
        if fn.return_type in C.ASYNC_RETURN_TYPES:
            return True
        return False

    def _is_batch_bean(self, fn: ParsedFunction) -> bool:
        if "Bean" not in fn.annotations:
            return False
        if fn.return_type in _BATCH_RETURN_TYPES:
            return True
        return bool(_BATCH_WIRE_RE.search(fn.body_text))

    def _node_layer(self, type_layer: Layer, fn: ParsedFunction) -> Layer:
        if self._is_batch_bean(fn):
            return Layer.BATCH
        return type_layer

    def _endpoint(self, fqcn: str, fn: ParsedFunction) -> tuple[Optional[str], Optional[str]]:
        """Resolve (httpMethod, full URL) for a controller endpoint, else (None, None)."""
        if fn.http_method is None and not fn.path:
            return None, None
        t = self.types_by_fqcn.get(fqcn, (None, None))[0]
        base = (t.base_path if t else None) or ""
        segments = [s for s in (base + "/" + (fn.path or "")).split("/") if s]
        full = "/" + "/".join(segments) if segments else "/"
        return fn.http_method, full

    @staticmethod
    def _compose_url(base: Optional[str], path: Optional[str]) -> Optional[str]:
        """Join a Feign base url with a method path; None if neither is known."""
        if not base and not path:
            return None
        b = (base or "").rstrip("/")
        p = (path or "")
        if p and not p.startswith("/"):
            p = "/" + p
        return (b + p) or None

    def _tracked(self, layer: Layer) -> bool:
        if layer in (Layer.EXTERNAL,):
            return False
        if layer == Layer.OTHER:
            return self.include_other
        return True

    # ---- type / call resolution ----

    def _resolve_type_fqcn(self, simple: Optional[str], pf: ParsedFile) -> Optional[str]:
        if not simple:
            return None
        simple = simple.split(".")[-1] if "." in simple else simple
        if simple in pf.imports:
            return pf.imports[simple]
        cand = pf.package + "." + simple if pf.package else simple
        if cand in self.types_by_fqcn:
            return cand
        fqs = self.types_by_simple.get(simple)
        if fqs and len(fqs) == 1:
            return fqs[0]
        if fqs:
            return fqs[0]  # ambiguous: best-effort first
        return None

    def _receiver_type(self, recv: Optional[str], fn: ParsedFunction,
                       owner: ParsedType) -> tuple[Optional[str], bool]:
        """Return (simple type name, is_static_receiver)."""
        if recv is None:
            return owner.simple_name, False
        for table in (fn.locals, fn.params, owner.ctor_fields, owner.fields):
            if recv in table:
                return table[recv], False
        # receiver may itself be a type name (companion/static access): Foo.bar()
        if recv in self.types_by_simple or recv[:1].isupper():
            return recv, True
        return None, False

    # ---- main build ----

    def build(self) -> CallGraph:
        nodes: dict[str, MethodNode] = {}
        edges: dict[tuple, CallEdge] = {}

        def add_node(node: MethodNode) -> None:
            nodes.setdefault(node.id, node)

        def node_for(fqcn: str, fn: ParsedFunction, type_layer: Layer,
                     pf: ParsedFile) -> MethodNode:
            nid = f"{fqcn}#{fn.name}"
            http_method, endpoint = self._endpoint(fqcn, fn)
            node = MethodNode(
                id=nid, fqcn=fqcn, method=fn.name,
                layer=self._node_layer(type_layer, fn),
                visibility=fn.visibility, is_async=self._is_async_fn(fn),
                return_type=fn.return_type, file=pf.path, line=fn.line,
                project=pf.project, module=pf.module,
                http_method=http_method, endpoint=endpoint,
            )
            add_node(node)
            return node

        # 1) nodes for tracked declarations
        type_layers: dict[str, Layer] = {}
        for pf in self.files:
            for t in pf.types:
                fq = self._fqcn(pf, t)
                layer = self._layer_of(t)
                type_layers[fq] = layer
                for fn in t.functions:
                    nl = self._node_layer(layer, fn)
                    if not self._tracked(nl):
                        continue
                    node_for(fq, fn, layer, pf)

        # 2) call edges
        for pf in self.files:
            for t in pf.types:
                fq = self._fqcn(pf, t)
                layer = type_layers[fq]
                for fn in t.functions:
                    nl = self._node_layer(layer, fn)
                    if not self._tracked(nl):
                        continue
                    from_id = f"{fq}#{fn.name}"
                    for call in fn.calls:
                        self._resolve_call(call, fn, t, pf, fq, from_id,
                                           nodes, edges, add_node)
                    # 3) batch wiring edges (within @Bean bodies)
                    if self._is_batch_bean(fn):
                        self._wire_batch(fn, t, fq, from_id, pf, nodes, edges, add_node)

        return CallGraph(list(nodes.values()), list(edges.values()))

    def _resolve_call(self, call, fn, owner, pf, owner_fq, from_id,
                      nodes, edges, add_node) -> None:
        simple_type, is_static = self._receiver_type(call.receiver, fn, owner)
        callee_fqcn = self._resolve_type_fqcn(simple_type, pf)

        def emit(target_id, mode, kind, relation):
            e = CallEdge(from_id, target_id, mode, kind, relation,
                         pf.path, call.line)
            edges.setdefault(e.key(), e)

        # external by simple type or import prefix
        if simple_type in C.EXTERNAL_SIMPLE_TYPES or self._external_by_import(simple_type, pf):
            tid = f"ext:{simple_type}#{call.name}"
            # URL is a runtime argument for these clients; service = client type.
            add_node(MethodNode(tid, simple_type, call.name, Layer.EXTERNAL,
                                "public", False, None, None, None,
                                pf.project, pf.module,
                                external_service=simple_type))
            emit(tid, CallMode.ASYNC if call.in_async_ctx else CallMode.SYNC,
                 EdgeKind.EXTERNAL, "call")
            return

        if callee_fqcn is None or callee_fqcn not in self.types_by_fqcn:
            return  # unresolved / out of scope

        callee_type, callee_pf = self.types_by_fqcn[callee_fqcn]
        callee_layer = self._layer_of(callee_type)

        # Feign client interface -> external (URL is statically known here)
        if self._is_feign(callee_type):
            tid = f"ext:{callee_type.simple_name}#{call.name}"
            target_fn = self._find_method(callee_fqcn, call.name)
            verb = target_fn.http_method if target_fn else None
            path = target_fn.path if target_fn else None
            service = callee_type.feign_name or callee_type.simple_name
            url = self._compose_url(callee_type.feign_url, path)
            add_node(MethodNode(
                tid, callee_fqcn, call.name, Layer.EXTERNAL, "public", False, None,
                callee_pf.path, target_fn.line if target_fn else None,
                callee_pf.project, callee_pf.module,
                http_method=verb, endpoint=path,
                external_service=service, external_url=url))
            emit(tid, CallMode.ASYNC if call.in_async_ctx else CallMode.SYNC,
                 EdgeKind.EXTERNAL, "call")
            return

        # find declared method
        target_fn = self._find_method(callee_fqcn, call.name)
        if target_fn is not None:
            tid = f"{callee_fqcn}#{call.name}"
            if tid not in nodes:
                self._make_node(callee_fqcn, target_fn, callee_layer, callee_pf, add_node)
            mode = (CallMode.ASYNC
                    if call.in_async_ctx or self._is_async_fn(target_fn)
                    else CallMode.SYNC)
            emit(tid, mode, EdgeKind.INTERNAL, "call")
            return

        # Spring Data inherited repository method (save/findById/...)
        if callee_layer == Layer.REPOSITORY and call.name in C.REPOSITORY_INHERITED_METHODS:
            tid = f"{callee_fqcn}#{call.name}"
            add_node(MethodNode(tid, callee_fqcn, call.name, Layer.REPOSITORY,
                                "public", False, None, callee_pf.path, None,
                                callee_pf.project, callee_pf.module))
            emit(tid, CallMode.ASYNC if call.in_async_ctx else CallMode.SYNC,
                 EdgeKind.INTERNAL, "call")

    def _make_node(self, fqcn, fn, layer, pf, add_node) -> None:
        http_method, endpoint = self._endpoint(fqcn, fn)
        add_node(MethodNode(
            id=f"{fqcn}#{fn.name}", fqcn=fqcn, method=fn.name,
            layer=self._node_layer(layer, fn), visibility=fn.visibility,
            is_async=self._is_async_fn(fn), return_type=fn.return_type,
            file=pf.path, line=fn.line, project=pf.project, module=pf.module,
            http_method=http_method, endpoint=endpoint))

    def _find_method(self, fqcn: str, name: str) -> Optional[ParsedFunction]:
        seen: set[str] = set()
        stack = [fqcn]
        while stack:
            cur = stack.pop()
            if cur in seen or cur not in self.types_by_fqcn:
                continue
            seen.add(cur)
            t, pf = self.types_by_fqcn[cur]
            for fn in t.functions:
                if fn.name == name:
                    return fn
            for sup in t.supertypes:
                sfq = self._resolve_type_fqcn(sup, pf)
                if sfq:
                    stack.append(sfq)
        return None

    def _external_by_import(self, simple: Optional[str], pf: ParsedFile) -> bool:
        if not simple:
            return False
        fq = pf.imports.get(simple)
        if not fq:
            return False
        return any(fq.startswith(p) for p in C.EXTERNAL_PREFIXES)

    def _wire_batch(self, fn, owner, owner_fq, from_id, pf, nodes, edges, add_node) -> None:
        sibling = {f.name: f for f in owner.functions}
        for m in _BATCH_WIRE_RE.finditer(fn.body_text):
            method, ident = m.group(1), m.group(2)
            relation = C.BATCH_WIRING_METHODS[method]
            target_fn = sibling.get(ident)
            # also: a param whose name matches a sibling bean (Spring by-name injection)
            if target_fn is None and ident in fn.params:
                target_fn = sibling.get(ident)
            if target_fn is None:
                continue
            tid = f"{owner_fq}#{target_fn.name}"
            if tid not in nodes:
                self._make_node(owner_fq, target_fn, type_layer := self._layer_of(owner),
                                pf, add_node)
            e = CallEdge(from_id, tid, CallMode.ASYNC, EdgeKind.BATCH, relation,
                         pf.path, fn.line)
            edges.setdefault(e.key(), e)


def build_graph(files: list[ParsedFile], *, include_other: bool = False) -> CallGraph:
    return GraphBuilder(files, include_other=include_other).build()

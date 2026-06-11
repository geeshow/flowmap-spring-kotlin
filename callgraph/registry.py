"""Cross-run service registry (`.flowmap/registry.json`).

Accumulates, across separate `analyze` runs, every analyzed service's:
  * exposed HTTP endpoints (httpMethod + path -> provider node)  → for S2S linking
  * Kafka producers / consumers per topic                        → for event S2S
so a newly analyzed service automatically links to services analyzed earlier
(and vice-versa) without analyzing them together.
"""
from __future__ import annotations

import json
import os
import re
from typing import Optional

from .model import CallGraph, EdgeKind, Layer

_VERSION = 1


def empty() -> dict:
    return {"version": _VERSION, "services": {}, "endpoints": [], "kafka": {}}


def load(path: Optional[str]) -> dict:
    if not path or not os.path.isfile(path):
        return empty()
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, json.JSONDecodeError):
        return empty()
    for k, v in empty().items():
        data.setdefault(k, v)
    return data


def save(path: str, reg: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(reg, fh, indent=2, ensure_ascii=False)


# --------------------------------------------------------------------------- #
# path normalization & lookup
# --------------------------------------------------------------------------- #

def norm_path(p: Optional[str]) -> str:
    if not p:
        return ""
    p = p.split("?")[0]
    p = re.sub(r"\{[^}]*\}", "{}", p)        # /users/{id} == /users/{userNo}
    if len(p) > 1:
        p = p.rstrip("/")
    return p


def resolve_endpoint(reg: dict, verb: Optional[str], path: Optional[str],
                     hint: Optional[str]) -> Optional[dict]:
    """Find a provider endpoint matching (httpMethod, path), preferring the service hint."""
    np = norm_path(path)
    if not np:
        return None
    cands = [e for e in reg["endpoints"] if norm_path(e.get("endpoint")) == np]
    if verb and verb != "ANY":
        verb_cands = [e for e in cands if e.get("httpMethod") in (verb, "ANY", None)]
        cands = verb_cands or cands
    if not cands:
        return None
    if hint:
        for e in cands:
            if e.get("project") == hint or hint in e.get("aliases", []):
                return e
    return cands[0]


def producers(reg: dict, topic: str) -> list[dict]:
    return reg["kafka"].get(topic, {}).get("producers", [])


def consumers(reg: dict, topic: str) -> list[dict]:
    return reg["kafka"].get(topic, {}).get("consumers", [])


# --------------------------------------------------------------------------- #
# update from a freshly built graph
# --------------------------------------------------------------------------- #

def update_from_graph(reg: dict, graph: CallGraph) -> dict:
    """Upsert this graph's endpoints + kafka producers/consumers into the registry."""
    node_by_id = {n.id: n for n in graph.nodes}

    # 0) record every analyzed service (even ones with no controller, e.g. consumers)
    for n in graph.nodes:
        if n.project:
            reg["services"].setdefault(n.project, {})

    # 1) HTTP endpoints exposed by controllers
    for n in graph.nodes:
        if n.layer == Layer.CONTROLLER and n.endpoint and n.project:
            rec = {
                "project": n.project, "nodeId": n.id, "fqcn": n.fqcn, "method": n.method,
                "httpMethod": n.http_method, "endpoint": n.endpoint,
                "description": n.description,
            }
            _upsert_endpoint(reg, rec)
            reg["services"].setdefault(n.project, {})

    # 2) Kafka producers/consumers (from resource edges)
    for e in graph.edges:
        if e.kind != EdgeKind.RESOURCE:
            continue
        if e.relation == "kafka:produce":
            topic = _topic_of(e.target)
            prod = node_by_id.get(e.source)
            if topic and prod:
                _upsert_kafka(reg, topic, "producers", _stub_rec(prod))
        elif e.relation == "kafka:consume":
            topic = _topic_of(e.source)
            cons = node_by_id.get(e.target)
            if topic and cons:
                _upsert_kafka(reg, topic, "consumers", _stub_rec(cons))
    return reg


def _topic_of(node_id: str) -> Optional[str]:
    return node_id[len("kafka:"):] if node_id.startswith("kafka:") else None


def _stub_rec(node) -> dict:
    return {"project": node.project, "nodeId": node.id, "fqcn": node.fqcn,
            "method": node.method, "layer": node.layer.value}


def _upsert_endpoint(reg: dict, rec: dict) -> None:
    key = (rec["project"], rec.get("httpMethod"), norm_path(rec.get("endpoint")))
    reg["endpoints"] = [e for e in reg["endpoints"]
                        if (e["project"], e.get("httpMethod"), norm_path(e.get("endpoint"))) != key]
    reg["endpoints"].append(rec)


def _upsert_kafka(reg: dict, topic: str, role: str, rec: dict) -> None:
    slot = reg["kafka"].setdefault(topic, {"producers": [], "consumers": []})
    slot[role] = [r for r in slot[role] if r.get("nodeId") != rec.get("nodeId")]
    slot[role].append(rec)

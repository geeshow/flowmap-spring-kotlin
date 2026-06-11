"""BFS over the call graph to extract a method's callers / callees subgraph."""
from __future__ import annotations

from collections import deque
from typing import Optional

from .model import CallEdge, CallGraph, MethodNode


def find_nodes(graph: CallGraph, query: str) -> list[MethodNode]:
    """Match by exact id, by 'method', or by 'fqcn#method' / substring."""
    q = query.strip()
    exact = [n for n in graph.nodes if n.id == q]
    if exact:
        return exact
    by_method = [n for n in graph.nodes if n.method == q]
    if by_method:
        return by_method
    ql = q.lower()
    return [n for n in graph.nodes
            if ql in n.id.lower() or ql in f"{n.fqcn}.{n.method}".lower()]


def bfs(graph: CallGraph, roots: list[str], *, direction: str = "both",
        depth: int = 3) -> CallGraph:
    """Return the subgraph reachable from ``roots`` within ``depth`` hops.

    direction: 'callees' (downstream / 호출), 'callers' (upstream / 피호출), 'both'.
    """
    out_adj: dict[str, list[CallEdge]] = {}
    in_adj: dict[str, list[CallEdge]] = {}
    for e in graph.edges:
        out_adj.setdefault(e.source, []).append(e)
        in_adj.setdefault(e.target, []).append(e)

    node_by_id = {n.id: n for n in graph.nodes}
    kept_nodes: set[str] = set(r for r in roots if r in node_by_id)
    kept_edges: dict[tuple, CallEdge] = {}

    follow_out = direction in ("both", "callees")
    follow_in = direction in ("both", "callers")

    visited: dict[str, int] = {r: 0 for r in roots if r in node_by_id}
    dq: deque[str] = deque(visited.keys())
    while dq:
        cur = dq.popleft()
        d = visited[cur]
        if d >= depth:
            continue
        if follow_out:
            for e in out_adj.get(cur, []):
                kept_edges[e.key()] = e
                kept_nodes.add(e.target)
                if e.target not in visited:
                    visited[e.target] = d + 1
                    dq.append(e.target)
        if follow_in:
            for e in in_adj.get(cur, []):
                kept_edges[e.key()] = e
                kept_nodes.add(e.source)
                if e.source not in visited:
                    visited[e.source] = d + 1
                    dq.append(e.source)

    nodes = [node_by_id[i] for i in kept_nodes if i in node_by_id]
    return CallGraph(nodes, list(kept_edges.values()))

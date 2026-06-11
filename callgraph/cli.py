"""Command-line interface.

  python -m callgraph analyze --repo .repo [--project P] [--out graph.json] [--include-other]
  python -m callgraph search  --method placeOrder [--repo .repo | --graph graph.json]
                              [--direction both|callers|callees] [--depth 3] [--out sub.json]
  python -m callgraph stats   [--repo .repo | --graph graph.json]
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter

from .build import build_graph
from .model import CallGraph, CallEdge, CallMode, EdgeKind, Layer, MethodNode
from .scanner import scan_repo
from .search import bfs, find_nodes


def _graph_from_args(args) -> CallGraph:
    if getattr(args, "graph", None):
        with open(args.graph, "r", encoding="utf-8") as fh:
            return _graph_from_json(json.load(fh))
    files = scan_repo(args.repo, project_filter=getattr(args, "project", None))
    return build_graph(files, include_other=getattr(args, "include_other", False))


def _graph_from_json(data: dict) -> CallGraph:
    nodes = [MethodNode(
        id=n["id"], fqcn=n["fqcn"], method=n["method"], layer=Layer(n["layer"]),
        visibility=n["visibility"], is_async=n["async"], return_type=n.get("returnType"),
        file=n.get("file"), line=n.get("line"), project=n.get("project"),
        module=n.get("module"), http_method=n.get("httpMethod"),
        endpoint=n.get("endpoint"), external_service=n.get("externalService"),
        external_url=n.get("externalUrl")) for n in data["nodes"]]
    edges = [CallEdge(
        source=e["source"], target=e["target"], mode=CallMode(e["mode"]),
        kind=EdgeKind(e["kind"]), relation=e["relation"],
        call_site_file=e.get("callSiteFile"), call_site_line=e.get("callSiteLine"))
        for e in data["edges"]]
    return CallGraph(nodes, edges)


def _dump(graph: CallGraph, out: str | None, meta: dict) -> None:
    payload = graph.to_node_link(meta)
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"wrote {out}: {len(graph.nodes)} nodes, {len(graph.edges)} edges",
              file=sys.stderr)
    else:
        print(text)


def cmd_analyze(args) -> int:
    files = scan_repo(args.repo, project_filter=args.project)
    graph = build_graph(files, include_other=args.include_other)
    meta = {"command": "analyze", "repo": args.repo, "project": args.project,
            "files": len(files), "nodes": len(graph.nodes), "edges": len(graph.edges)}
    _dump(graph, args.out, meta)
    return 0


def cmd_search(args) -> int:
    graph = _graph_from_args(args)
    matches = find_nodes(graph, args.method)
    if not matches:
        print(f"no node matches '{args.method}'", file=sys.stderr)
        return 1
    if len(matches) > 1:
        print(f"matched {len(matches)} nodes for '{args.method}':", file=sys.stderr)
        for m in matches:
            print(f"  - {m.id}  ({m.layer.value}, {m.file}:{m.line})", file=sys.stderr)
    roots = [m.id for m in matches]
    sub = bfs(graph, roots, direction=args.direction, depth=args.depth)
    meta = {"command": "search", "query": args.method, "roots": roots,
            "direction": args.direction, "depth": args.depth,
            "nodes": len(sub.nodes), "edges": len(sub.edges)}
    _dump(sub, args.out, meta)
    return 0


def cmd_stats(args) -> int:
    graph = _graph_from_args(args)
    layers = Counter(n.layer.value for n in graph.nodes)
    kinds = Counter(e.kind.value for e in graph.edges)
    modes = Counter(e.mode.value for e in graph.edges)
    print(f"nodes: {len(graph.nodes)}   edges: {len(graph.edges)}")
    print("layers:", dict(layers))
    print("edge kinds:", dict(kinds))
    print("edge modes:", dict(modes))
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="callgraph", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("analyze", help="scan a repo and emit a node-link graph")
    a.add_argument("--repo", default=".repo")
    a.add_argument("--project", default=None, help="limit to .repo/<project>")
    a.add_argument("--out", default=None, help="output JSON path (default: stdout)")
    a.add_argument("--include-other", action="store_true",
                   help="also include unannotated (OTHER) classes")
    a.set_defaults(func=cmd_analyze)

    s = sub.add_parser("search", help="BFS callers/callees of a method")
    s.add_argument("--method", required=True, help="method name, fqcn#method, or substring")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--repo", default=".repo")
    g.add_argument("--graph", default=None, help="prebuilt graph.json to query")
    s.add_argument("--project", default=None)
    s.add_argument("--include-other", action="store_true")
    s.add_argument("--direction", choices=["both", "callers", "callees"], default="both")
    s.add_argument("--depth", type=int, default=3)
    s.add_argument("--out", default=None)
    s.set_defaults(func=cmd_search)

    st = sub.add_parser("stats", help="print layer/edge summary")
    gg = st.add_mutually_exclusive_group()
    gg.add_argument("--repo", default=".repo")
    gg.add_argument("--graph", default=None)
    st.add_argument("--project", default=None)
    st.add_argument("--include-other", action="store_true")
    st.set_defaults(func=cmd_stats)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)

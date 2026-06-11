"""Attach Korean API descriptions from Spring REST Docs snippets.

Spring REST Docs emits, per documented operation, a directory of `.adoc` snippets
under (by default) `build/generated-snippets/<operationId>/`. We read:

  * `http-request.adoc`  -> the HTTP method + request path of the operation
  * `description.adoc`   -> (convention) a one-line Korean description, IF present

and build a {(METHOD, normalized-path) -> description} map that the analyzer
attaches to the matching controller endpoint node (which then flows into the
registry, so S2S edges to that endpoint carry the description too).

Why a `description.adoc` convention: stock REST Docs snippets do NOT contain a
human description field — they document request/response *shape*. To surface a
Korean sentence, document it explicitly, e.g.:

    this.mockMvc.perform(get("/internal/users/{id}", 1))
        .andDo(document("get-user",
            snippet("description", "사용자 단건 조회")))   // writes description.adoc

If no `description.adoc` exists we fall back to the operationId (folder name) so
at least the endpoint is labeled. (For richer text, restdocs-api-spec / springdoc
OpenAPI are alternatives — see README.)
"""
from __future__ import annotations

import os
import re
from typing import Optional

_REQ_LINE_RE = re.compile(r"^\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s+(\S+)", re.M)


def normalize(path: Optional[str]) -> str:
    """Normalize a path for matching: {var} and concrete id segments collapse to {}."""
    if not path:
        return ""
    p = path.split("?")[0]
    parts = []
    for seg in p.split("/"):
        if not seg:
            continue
        if seg.startswith("{") or _looks_like_id(seg):
            parts.append("{}")
        else:
            parts.append(seg)
    return "/" + "/".join(parts)


def _looks_like_id(seg: str) -> bool:
    if seg.isdigit():
        return True
    # uuid-ish
    return bool(re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", seg))


def load(restdocs_dir: Optional[str]) -> dict[tuple[str, str], str]:
    """Return {(httpMethod, normalizedPath) -> description}."""
    out: dict[tuple[str, str], str] = {}
    if not restdocs_dir or not os.path.isdir(restdocs_dir):
        return out
    for root, _dirs, fnames in os.walk(restdocs_dir):
        if "http-request.adoc" not in fnames:
            continue
        verb, path = _parse_request(os.path.join(root, "http-request.adoc"))
        if not verb:
            continue
        desc = _read_description(root) or os.path.basename(root)
        out[(verb, normalize(path))] = desc
    return out


def _parse_request(adoc_path: str) -> tuple[Optional[str], Optional[str]]:
    try:
        with open(adoc_path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return None, None
    m = _REQ_LINE_RE.search(text)
    if not m:
        return None, None
    return m.group(1), m.group(2)


def _read_description(op_dir: str) -> Optional[str]:
    path = os.path.join(op_dir, "description.adoc")
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if s and not s.startswith(("=", "[", "-", "/")):
                    return s
    except OSError:
        return None
    return None

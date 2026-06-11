"""Walk a ``.repo/<project>/<module>`` tree and parse every source file."""
from __future__ import annotations

import os
from typing import Optional

from .sourceparse import ParsedFile, parse_source

_SKIP_DIRS = {".git", "build", "out", "target", "node_modules", ".gradle", ".idea"}


def _provenance(repo_root: str, abspath: str) -> tuple[Optional[str], Optional[str]]:
    """Infer (project, module) from .repo/<project>/<module>/... layout."""
    rel = os.path.relpath(abspath, repo_root)
    parts = rel.split(os.sep)
    project = parts[0] if len(parts) >= 1 else None
    module = parts[1] if len(parts) >= 2 else None
    return project, module


def scan_repo(repo_root: str, *, project_filter: Optional[str] = None) -> list[ParsedFile]:
    repo_root = os.path.abspath(repo_root)
    parsed: list[ParsedFile] = []
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [d for d in dirnames if d not in _SKIP_DIRS]
        for fn in filenames:
            if not (fn.endswith(".kt") or fn.endswith(".java")):
                continue
            abspath = os.path.join(dirpath, fn)
            project, module = _provenance(repo_root, abspath)
            if project_filter and project != project_filter:
                continue
            try:
                with open(abspath, "r", encoding="utf-8", errors="replace") as fh:
                    src = fh.read()
            except OSError:
                continue
            relpath = os.path.relpath(abspath, repo_root)
            parsed.append(parse_source(relpath, src, project=project, module=module))
    return parsed

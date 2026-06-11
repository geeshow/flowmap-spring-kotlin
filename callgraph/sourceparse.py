"""Heuristic Kotlin/Java source parser (pure stdlib).

Strategy
--------
1. ``clean()`` blanks out comments and string/char literals while preserving every
   newline, so byte offsets still map to the original line numbers.
2. We locate every type (class/interface/object) and function declaration with regex,
   then compute each one's body byte-range via brace matching.
3. Containment of byte-ranges assigns functions to their enclosing type and calls to
   their enclosing function -- which handles nested classes and local functions.

This is intentionally a heuristic: it understands idiomatic, brace-formatted Spring
Kotlin/Java, not every corner of either grammar. Limitations are documented in README.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

from .classify import ASYNC_BUILDERS, CALL_KEYWORD_BLACKLIST


# --------------------------------------------------------------------------- #
# cleaning
# --------------------------------------------------------------------------- #

def clean(src: str) -> str:
    """Replace comments and string/char literals with spaces, keeping newlines.

    The result has the same length as ``src`` so any index maps to the same line.
    """
    out = list(src)
    i, n = 0, len(src)
    state = "code"  # code | line_comment | block_comment | string | char | raw_string
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line_comment"; out[i] = " "; out[i + 1] = " "; i += 2; continue
            if c == "/" and nxt == "*":
                state = "block_comment"; out[i] = " "; out[i + 1] = " "; i += 2; continue
            if c == '"' and src[i:i + 3] == '"""':
                state = "raw_string"; out[i] = out[i + 1] = out[i + 2] = " "; i += 3; continue
            if c == '"':
                state = "string"; out[i] = " "; i += 1; continue
            if c == "'":
                state = "char"; out[i] = " "; i += 1; continue
            i += 1; continue
        # inside a non-code region: blank everything except newlines
        if c != "\n":
            out[i] = " "
        if state == "line_comment":
            if c == "\n":
                state = "code"
        elif state == "block_comment":
            if c == "*" and nxt == "/":
                out[i + 1] = " "; i += 2; state = "code"; continue
        elif state == "raw_string":
            if src[i:i + 3] == '"""':
                out[i + 1] = out[i + 2] = " "; i += 3; state = "code"; continue
        elif state == "string":
            if c == "\\":
                if nxt != "\n":
                    out[i + 1] = " "
                i += 2; continue
            if c == '"':
                state = "code"
        elif state == "char":
            if c == "\\":
                out[i + 1] = " "; i += 2; continue
            if c == "'":
                state = "code"
        i += 1
    return "".join(out)


def _line_starts(src: str) -> list[int]:
    starts = [0]
    for i, c in enumerate(src):
        if c == "\n":
            starts.append(i + 1)
    return starts


def _line_of(starts: list[int], index: int) -> int:
    # binary search: largest start <= index
    lo, hi = 0, len(starts) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if starts[mid] <= index:
            lo = mid
        else:
            hi = mid - 1
    return lo + 1  # 1-based


def _match_braces(cleaned: str, open_index: int) -> int:
    """Given the index of a ``{``, return the index just past its matching ``}``."""
    depth = 0
    i, n = open_index, len(cleaned)
    while i < n:
        c = cleaned[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def _strip_generics(t: Optional[str]) -> Optional[str]:
    if not t:
        return t
    t = t.strip().lstrip("@").strip()
    t = re.sub(r"<.*", "", t)          # drop generic args
    t = t.rstrip("?").strip()          # nullable
    t = t.split(".")[-1] if "." not in t[:0] else t  # keep dotted for fqcn detect
    return t or None


# --------------------------------------------------------------------------- #
# parsed structures
# --------------------------------------------------------------------------- #

@dataclass
class ParsedCall:
    name: str
    receiver: Optional[str]
    line: int
    in_async_ctx: bool


@dataclass
class ParsedFunction:
    name: str
    line: int
    header_start: int
    body_start: int       # byte index of first '{' or expr '='; -1 if abstract
    body_end: int
    modifiers: set[str]
    annotations: list[str]
    return_type: Optional[str]
    http_method: Optional[str] = None   # GET/POST/... from @*Mapping
    path: Optional[str] = None          # method-level mapping path
    params: dict[str, str] = field(default_factory=dict)
    locals: dict[str, str] = field(default_factory=dict)
    calls: list[ParsedCall] = field(default_factory=list)
    visibility: str = "public"
    body_text: str = ""
    kafka_produced: list[str] = field(default_factory=list)   # topics sent via KafkaTemplate
    kafka_consumed: list[str] = field(default_factory=list)   # @KafkaListener topics

    @property
    def is_async(self) -> bool:
        return False  # filled by builder using annotations/modifiers/return type


@dataclass
class ParsedType:
    simple_name: str
    kind: str             # class | interface | object
    annotations: list[str]
    supertypes: list[str]
    ctor_fields: dict[str, str]
    fields: dict[str, str]
    functions: list[ParsedFunction]
    line: int
    header_start: int
    body_start: int
    body_end: int
    base_path: Optional[str] = None     # class-level @RequestMapping base path
    feign_name: Optional[str] = None    # @FeignClient(name=...)
    feign_url: Optional[str] = None     # @FeignClient(url=...)
    is_entity: bool = False             # @Entity
    table_name: Optional[str] = None    # @Table(name=...) or derived
    repo_entity: Optional[str] = None   # entity type from JpaRepository<Entity, Id>


@dataclass
class ParsedFile:
    path: str             # repo-relative
    project: Optional[str]
    module: Optional[str]
    language: str         # kotlin | java
    package: str
    imports: dict[str, str]          # simple name -> fqcn
    types: list[ParsedType]


# --------------------------------------------------------------------------- #
# regexes
# --------------------------------------------------------------------------- #

_PKG_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
_IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)(?:\s+as\s+(\w+))?", re.M)
_TYPE_RE = re.compile(r"\b(class|interface|object)\s+(\w+)")
_FUN_RE = re.compile(r"\bfun\b(?:\s*<[^>]*>)?\s+(?:[\w.]+\.)?(\w+)\s*\(")
_JAVA_METHOD_RE = re.compile(
    r"(?:public|private|protected|static|final|abstract|synchronized|native|\s)+"
    r"(?:[\w.<>\[\],?\s]+?)\s+(\w+)\s*\([^;{]*\)\s*(?:throws[\w.,\s]+)?\{"
)
_PARAM_RE = re.compile(r"(?:val|var|final)?\s*(\w+)\s*:\s*([\w.]+(?:<[^>]*>)?)")
_LOCAL_KT_RE = re.compile(
    r"\b(?:val|var)\s+(\w+)\s*(?::\s*([\w.]+(?:<[^>]*>)?))?\s*=\s*([\w.]+)\s*\(")
_CALL_RE = re.compile(r"(?:(\w+)\s*[.?]+\s*)?(\w+)\s*\(")
_ASYNC_BUILDER_RE = re.compile(
    r"\b(" + "|".join(sorted(ASYNC_BUILDERS, key=len, reverse=True)) + r")\b\s*(?:\([^{)]*\))?\s*\{")
_ANNOTATION_RE = re.compile(r"@(\w+)")
_VIS_RE = re.compile(r"\b(private|protected|internal|public)\b")
_FIELD_KT_RE = re.compile(r"\b(?:val|var)\s+(\w+)\s*:\s*([\w.]+(?:<[^>]*>)?)")


_MAPPING_RE = re.compile(
    r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(\(([^()]*)\))?")
_HTTP_VERB = {"Get": "GET", "Post": "POST", "Put": "PUT",
              "Delete": "DELETE", "Patch": "PATCH"}
_REQ_METHOD_RE = re.compile(r"RequestMethod\.(\w+)")


def _extract_mapping(window: str) -> tuple[Optional[str], Optional[str]]:
    """Pull (httpMethod, path) from a Spring @*Mapping annotation in *original* source.

    ``window`` is original text (not comment/string-stripped) just before a declaration.
    Returns (None, None) if no mapping annotation is present.
    """
    m = None
    for m in _MAPPING_RE.finditer(window):  # keep the last (closest to declaration)
        pass
    if m is None:
        return None, None
    kind, args = m.group(1), m.group(3) or ""
    if kind == "Request":
        verb_m = _REQ_METHOD_RE.search(args)
        verb = verb_m.group(1) if verb_m else "ANY"
    else:
        verb = _HTTP_VERB[kind]
    path = _extract_path(args)
    return verb, path


_FEIGN_RE = re.compile(r"@FeignClient\b\s*\(([^)]*)\)")


def _extract_feign(window: str) -> tuple[Optional[str], Optional[str]]:
    """Return (name, url) from a @FeignClient annotation in *original* source."""
    m = _FEIGN_RE.search(window)
    if m is None:
        # bare @FeignClient with no args
        return (None, None) if "@FeignClient" not in window else (None, None)
    args = m.group(1)
    name_m = re.search(r'name\s*=\s*"([^"]*)"', args)
    url_m = re.search(r'url\s*=\s*"([^"]*)"', args)
    if not name_m and not url_m:
        # single positional arg is the name
        first = re.search(r'"([^"]*)"', args)
        return (first.group(1) if first else None), None
    return (name_m.group(1) if name_m else None), (url_m.group(1) if url_m else None)


def _extract_path(args: str) -> str:
    if not args:
        return ""
    m = re.search(r'(?:value|path)\s*=\s*\[?\s*"([^"]*)"', args)
    if m:
        return m.group(1)
    m = re.search(r'"([^"]*)"', args)
    return m.group(1) if m else ""


_TABLE_RE = re.compile(r"@Table\b\s*\([^)]*?name\s*=\s*\"([^\"]+)\"")
_REPO_GENERIC_RE = re.compile(
    r"\b(?:Jpa|Crud|PagingAndSorting|ListCrud|Reactive(?:Crud)?|Coroutine(?:Crud)?)Repository"
    r"\s*<\s*([\w.]+)")
_KAFKA_LISTENER_RE = re.compile(r"@KafkaListener\b\s*\(([^)]*)\)", re.S)
_KAFKA_SEND_RE = re.compile(r"\.\s*send(?:Default)?\s*\(\s*\"([^\"]+)\"")
_STRING_LIT_RE = re.compile(r'"([^"]+)"')


def _extract_entity_table(window: str, simple_name: str) -> tuple[bool, Optional[str]]:
    """(is_entity, table_name) from @Entity/@Table in *original* source window."""
    if "@Entity" not in window:
        return False, None
    m = _TABLE_RE.search(window)
    if m:
        return True, m.group(1)
    # default JPA table name = entity simple name (left as-is; callers may lower-case)
    return True, simple_name


def _extract_kafka_consumed(window: str) -> list[str]:
    """Topics from @KafkaListener(topics = [...]) in *original* source window."""
    m = _KAFKA_LISTENER_RE.search(window)
    if not m:
        return []
    args = m.group(1)
    topics_m = re.search(r"topics\s*=\s*(\[[^\]]*\]|\"[^\"]*\")", args)
    seg = topics_m.group(1) if topics_m else args
    return _STRING_LIT_RE.findall(seg)


def _collect_annotations_before(cleaned: str, start: int) -> list[str]:
    """Scan backwards from a declaration start to gather preceding @Annotations."""
    # take up to 400 chars before, stop at a line that is neither blank nor an annotation
    head = cleaned[max(0, start - 600):start]
    lines = head.splitlines()
    anns: list[str] = []
    for ln in reversed(lines):
        s = ln.strip()
        if not s:
            continue
        if s.startswith("@"):
            anns.extend(_ANNOTATION_RE.findall(s))
        elif s.endswith(")") and "@" in s:   # annotation arg spanning, keep scanning
            anns.extend(_ANNOTATION_RE.findall(s))
        else:
            break
    return list(reversed(anns))


def _parse_params(header: str) -> dict[str, str]:
    """Extract name->type from a parenthesised param list (Kotlin & Java)."""
    m = header.find("(")
    if m < 0:
        return {}
    depth = 0
    end = m
    for i in range(m, len(header)):
        if header[i] == "(":
            depth += 1
        elif header[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    params_str = header[m + 1:end]
    out: dict[str, str] = {}
    for name, typ in _PARAM_RE.findall(params_str):
        if name in ("val", "var", "final"):
            continue
        out[name] = _strip_generics(typ)
    # Java style "Type name"
    for chunk in params_str.split(","):
        parts = chunk.strip().split()
        if len(parts) == 2 and ":" not in chunk:
            jtype, jname = parts
            out.setdefault(jname.strip("."), _strip_generics(jtype))
    return out


# --------------------------------------------------------------------------- #
# main entry
# --------------------------------------------------------------------------- #

def parse_source(path: str, src: str, *, project=None, module=None) -> ParsedFile:
    language = "kotlin" if path.endswith(".kt") else "java"
    cleaned = clean(src)
    starts = _line_starts(src)

    pkg_m = _PKG_RE.search(cleaned)
    package = pkg_m.group(1) if pkg_m else ""

    imports: dict[str, str] = {}
    for fqcn, alias in _IMPORT_RE.findall(cleaned):
        simple = alias or fqcn.split(".")[-1]
        imports[simple] = fqcn

    # ---- types ----
    raw_types: list[ParsedType] = []
    for m in _TYPE_RE.finditer(cleaned):
        kind, name = m.group(1), m.group(2)
        hstart = m.start()
        # header runs until the body '{' (balancing parens/angles) or a statement end
        body_open = _find_body_open(cleaned, m.end())
        if body_open is None:
            # bodyless (e.g. marker interface / object expression) — skip range
            header = cleaned[hstart:_stmt_end(cleaned, m.end())]
            bstart, bend = -1, m.end()
        else:
            header = cleaned[hstart:body_open]
            bstart = body_open
            bend = _match_braces(cleaned, body_open)
        anns = _collect_annotations_before(cleaned, hstart)
        ann_window = src[max(0, hstart - 400):hstart]
        _, base_path = _extract_mapping(ann_window)
        feign_name, feign_url = _extract_feign(ann_window)
        is_entity, table_name = _extract_entity_table(ann_window, name)
        repo_m = _REPO_GENERIC_RE.search(header)
        repo_entity = repo_m.group(1).split(".")[-1] if repo_m else None
        supertypes = _parse_supertypes(header)
        ctor_fields = _parse_params(header)
        raw_types.append(ParsedType(
            base_path=base_path, feign_name=feign_name, feign_url=feign_url,
            is_entity=is_entity, table_name=table_name, repo_entity=repo_entity,
            simple_name=name, kind=kind, annotations=anns, supertypes=supertypes,
            ctor_fields=ctor_fields, fields={}, functions=[],
            line=_line_of(starts, hstart), header_start=hstart,
            body_start=bstart, body_end=bend,
        ))

    # ---- functions ----
    raw_funcs: list[ParsedFunction] = []
    fun_pattern = _FUN_RE if language == "kotlin" else _JAVA_METHOD_RE
    for m in fun_pattern.finditer(cleaned):
        name = m.group(1)
        if name in CALL_KEYWORD_BLACKLIST:
            continue
        hstart = m.start()
        body_open = _find_body_open(cleaned, m.end() - 1)
        expr_eq = _find_expr_body(cleaned, m.end() - 1) if language == "kotlin" else None
        if body_open is not None and (expr_eq is None or body_open < expr_eq):
            header = cleaned[hstart:body_open]
            bstart = body_open
            bend = _match_braces(cleaned, body_open)
            body_text = cleaned[bstart:bend]
        elif expr_eq is not None:
            # Skip whitespace/newlines after '=' so a multi-line expression body
            # (`fun f() =\n    expr`) is captured, not truncated at the first newline.
            b = expr_eq
            while b < len(cleaned) and cleaned[b] in " \t\r\n":
                b += 1
            header = cleaned[hstart:expr_eq]
            bstart = b
            bend = _stmt_end(cleaned, b)
            body_text = cleaned[bstart:bend]
        else:
            # abstract / interface method, no body
            header = cleaned[hstart:_stmt_end(cleaned, m.end())]
            bstart, bend, body_text = -1, m.end(), ""
        anns = _collect_annotations_before(cleaned, hstart)
        mods = set(re.findall(r"\b(suspend|override|open|abstract|private|protected|internal|public|static|final)\b",
                              cleaned[max(0, hstart - 40):hstart + 6]))
        vis_m = _VIS_RE.search(cleaned[max(0, hstart - 40):hstart])
        visibility = vis_m.group(1) if vis_m else "public"
        ret = _parse_return_type(header, language)
        params = _parse_params(header)
        fn_http, fn_path = _extract_mapping(src[max(0, hstart - 400):hstart])
        kafka_consumed = _extract_kafka_consumed(src[max(0, hstart - 400):hstart])
        # Kafka producer topics need the ORIGINAL body (string literals are blanked in `cleaned`).
        kafka_produced = (_KAFKA_SEND_RE.findall(src[bstart:bend]) if bstart >= 0 else [])
        raw_funcs.append(ParsedFunction(
            name=name, line=_line_of(starts, hstart), header_start=hstart,
            body_start=bstart, body_end=bend, modifiers=mods, annotations=anns,
            return_type=ret, http_method=fn_http, path=fn_path,
            params=params, visibility=visibility, body_text=body_text,
            kafka_produced=list(dict.fromkeys(kafka_produced)),
            kafka_consumed=kafka_consumed,
        ))

    # ---- assign functions to innermost containing type ----
    for fn in raw_funcs:
        owner = _innermost_type(raw_types, fn.header_start)
        if owner is not None:
            owner.functions.append(fn)
            _extract_function_internals(cleaned, starts, fn)

    # ---- class-level fields (outside any function body) ----
    for t in raw_types:
        if t.body_start < 0:
            continue
        _extract_type_fields(cleaned, t)

    return ParsedFile(
        path=path, project=project, module=module, language=language,
        package=package, imports=imports, types=raw_types,
    )


# --------------------------------------------------------------------------- #
# helpers for header / body delimiting
# --------------------------------------------------------------------------- #

def _find_body_open(cleaned: str, start: int) -> Optional[int]:
    """Find the '{' that opens a body, balancing () and <> we cross on the way."""
    depth_paren = depth_angle = 0
    i, n = start, len(cleaned)
    while i < n:
        c = cleaned[i]
        if c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren -= 1
        elif c == "<":
            depth_angle += 1
        elif c == ">":
            if depth_angle > 0:
                depth_angle -= 1
        elif c == "{" and depth_paren <= 0:
            return i
        elif c in ";\n" and depth_paren <= 0:
            # newline alone doesn't end a header unless followed by non-continuation;
            # only ';' truly ends it here. Keep scanning past newlines.
            if c == ";":
                return None
        i += 1
    return None


def _find_expr_body(cleaned: str, start: int) -> Optional[int]:
    """For Kotlin expression bodies: find '=' that begins ``fun f() = expr``."""
    depth_paren = depth_angle = 0
    i, n = start, len(cleaned)
    while i < n:
        c = cleaned[i]
        if c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren -= 1
        elif c == "<":
            depth_angle += 1
        elif c == ">" and depth_angle > 0:
            depth_angle -= 1
        elif depth_paren <= 0 and depth_angle <= 0:
            if c == "=" and (i + 1 >= n or cleaned[i + 1] not in "=<>!"):
                return i + 1
            if c == "{":
                return None
            if c == ";":
                return None
        i += 1
    return None


def _stmt_end(cleaned: str, start: int) -> int:
    """End of a statement / expression body: newline at top paren/brace depth."""
    depth = 0
    i, n = start, len(cleaned)
    while i < n:
        c = cleaned[i]
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
        elif c == "\n" and depth <= 0:
            return i
        i += 1
    return n


def _parse_supertypes(header: str) -> list[str]:
    # Kotlin: after ')' or name comes ': A, B(...)'; Java: 'extends X implements Y, Z'
    types: list[str] = []
    # Java
    jm = re.search(r"\b(?:extends|implements)\b(.*)", header)
    if jm:
        for t in re.split(r"[,\s]+", jm.group(1)):
            t = _strip_generics(t)
            if t and t not in ("extends", "implements"):
                types.append(t)
    # Kotlin ': Base(...), Iface'
    km = re.search(r"\)\s*:\s*(.+)$|\b\w+\s*:\s*([A-Z][\w.]*(?:\s*\([^)]*\))?(?:\s*,.*)?)$", header)
    colon = re.search(r":\s*(.+)$", header.replace("\n", " "))
    if colon and not jm:
        seg = colon.group(1)
        for part in seg.split(","):
            t = _strip_generics(part.split("(")[0])
            if t and t[0:1].isupper():
                types.append(t)
    return list(dict.fromkeys(types))


def _parse_return_type(header: str, language: str) -> Optional[str]:
    if language == "kotlin":
        # fun name(...) : Ret
        m = re.search(r"\)\s*:\s*([\w.]+(?:<[^>]*>)?)", header)
        return _strip_generics(m.group(1)) if m else None
    # Java: modifiers Ret name(
    m = re.search(r"([\w.<>\[\],?]+)\s+\w+\s*\(", header)
    return _strip_generics(m.group(1)) if m else None


def _innermost_type(types: list[ParsedType], index: int) -> Optional[ParsedType]:
    best = None
    for t in types:
        if t.body_start <= index < t.body_end:
            if best is None or t.body_start > best.body_start:
                best = t
    return best


# --------------------------------------------------------------------------- #
# function internals: locals, calls, async contexts
# --------------------------------------------------------------------------- #

def _extract_function_internals(cleaned: str, starts: list[int], fn: ParsedFunction) -> None:
    if fn.body_start < 0:
        return
    body = cleaned[fn.body_start:fn.body_end]
    base = fn.body_start

    # locals
    for name, typ, ctor in _LOCAL_KT_RE.findall(body):
        fn.locals[name] = _strip_generics(typ or ctor)

    # async-context byte ranges within the body (absolute indices)
    async_ranges: list[tuple[int, int]] = []
    for am in _ASYNC_BUILDER_RE.finditer(body):
        open_idx = body.find("{", am.start())
        if open_idx < 0:
            continue
        end_idx = _match_braces(body, open_idx)
        async_ranges.append((base + open_idx, base + end_idx))

    def in_async(abs_idx: int) -> bool:
        return any(lo <= abs_idx < hi for lo, hi in async_ranges)

    # calls
    for cm in _CALL_RE.finditer(body):
        recv, name = cm.group(1), cm.group(2)
        if name in CALL_KEYWORD_BLACKLIST:
            continue
        if recv in CALL_KEYWORD_BLACKLIST:
            recv = None
        abs_idx = base + cm.start()
        fn.calls.append(ParsedCall(
            name=name, receiver=recv,
            line=_line_of(starts, abs_idx),
            in_async_ctx=in_async(abs_idx),
        ))


def _extract_type_fields(cleaned: str, t: ParsedType) -> None:
    """Collect class-body 'val/var name: Type' that are not inside a function body."""
    body = cleaned[t.body_start:t.body_end]
    func_ranges = [(f.body_start, f.body_end) for f in t.functions if f.body_start >= 0]
    for fm in _FIELD_KT_RE.finditer(body):
        abs_idx = t.body_start + fm.start()
        if any(lo <= abs_idx < hi for lo, hi in func_ranges):
            continue
        t.fields[fm.group(1)] = _strip_generics(fm.group(2))

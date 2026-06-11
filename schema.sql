-- Schema (PostgresSync also creates this automatically)

CREATE TABLE IF NOT EXISTS method_node (
    id          TEXT PRIMARY KEY,
    fqcn        TEXT NOT NULL,
    method      TEXT NOT NULL,
    descriptor  TEXT,
    layer       TEXT,           -- CONTROLLER / SERVICE / REPOSITORY / COMPONENT / EXTERNAL
    visibility  TEXT,
    source_file TEXT,
    source_path TEXT,           -- code path: resolved file
    line        INT,            -- code path: declaration line
    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS call_edge (
    from_id        TEXT NOT NULL,
    to_id          TEXT NOT NULL,
    call_site_line INT,         -- code path: exact line of the call in the caller
    call_site_file TEXT,
    kind           TEXT,        -- INTERNAL / EXTERNAL
    snapshot_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_call_edge_from ON call_edge(from_id);
CREATE INDEX IF NOT EXISTS idx_call_edge_to   ON call_edge(to_id);


-- Example: direct controller -> service -> repository edges, with code paths
SELECT s.fqcn AS caller, e.call_site_file, e.call_site_line,
       t.fqcn AS callee, t.layer, t.source_path, t.line
FROM call_edge e
JOIN method_node s ON s.id = e.from_id
JOIN method_node t ON t.id = e.to_id
ORDER BY s.layer, s.fqcn;


-- Example: full call PATH from any controller down to repositories/external,
-- using a recursive traversal (the "code path" as a chain).
WITH RECURSIVE walk AS (
    SELECT n.id AS start_id, n.id AS node_id, n.fqcn || '#' || n.method AS path, 0 AS depth
    FROM method_node n
    WHERE n.layer = 'CONTROLLER'
  UNION ALL
    SELECT w.start_id, e.to_id, w.path || ' -> ' || t.fqcn || '#' || t.method, w.depth + 1
    FROM walk w
    JOIN call_edge e ON e.from_id = w.node_id
    JOIN method_node t ON t.id = e.to_id
    WHERE w.depth < 8
)
SELECT path
FROM walk w
JOIN method_node n ON n.id = w.node_id
WHERE n.layer IN ('REPOSITORY', 'EXTERNAL')
ORDER BY path;

# SQLite Session FUSE PoC

This prototype stores Kodex sessions in normalized SQLite tables and projects
them back to the current `sessions/` layout through a read-only FUSE mount.

## Scope

- SQLite is the source of truth.
- Six sparse timelines use six tables.
- Parent-child agent relationships use one recursive table.
- `latest.json` is generated from `MAX(state_index)`.
- `archive.mark` is projected from root-agent metadata.
- `lock.json` and `.kodex-*` files are excluded as runtime-only artifacts.
- The FUSE layer accepts any SQL view with the `fuse_nodes` column contract.
- Writes through FUSE are intentionally rejected.

## Run

Import only a quiescent session tree. The importer does not take the existing
filesystem lease.

```shell
cd experiments/sqlite-session-fuse
uv run kodex_session_fuse.py import ~/.kodex/sessions /tmp/kodex-sessions.db
mkdir /tmp/kodex-sqlite-home
uv run kodex_session_fuse.py mount \
  /tmp/kodex-sessions.db \
  /tmp/kodex-sqlite-home
```

Unmount from another terminal:

```shell
fusermount3 -u /tmp/kodex-sqlite-home
```

The mounted paths follow the current shape:

```text
sessions/<root-index>/
├── archive.mark
├── compaction/<state-index>.json
├── settings/<state-index>.json
├── stable/<state-index>.json
├── subagents/<entry-index>/...
├── timestamp/<state-index>.json
├── token-count/<state-index>.json
└── unstable/<state-index>.json
```

Every timeline also contains a generated `latest.json`.

## Query and configure

`agent_summary` exposes commonly useful values without reading individual
files:

```shell
uv run --no-project python - <<'PY'
import sqlite3

with sqlite3.connect("/tmp/kodex-sessions.db") as db:
    for row in db.execute(
        "SELECT path, thread_name, model, latest_token_count FROM agent_summary"
    ):
        print(row)
PY
```

The FUSE implementation is generic over this view contract:

```text
path, parent_path, name, kind, content, mtime_ns
```

For example, SQL can hide the unstable timeline without changing Python:

```sql
CREATE VIEW stable_only_nodes AS
SELECT *
FROM fuse_nodes
WHERE name <> 'unstable'
  AND path NOT LIKE '%/unstable/%';
```

Mount it with:

```shell
uv run kodex_session_fuse.py mount \
  /tmp/kodex-sessions.db \
  /tmp/kodex-sqlite-home \
  --view stable_only_nodes
```

Validate a custom projection before mounting:

```shell
uv run kodex_session_fuse.py validate \
  /tmp/kodex-sessions.db \
  --view stable_only_nodes \
  --deep
```

## Test

The default tests use real files and SQLite:

```shell
uv run test_kodex_session_fuse.py
```

Run the real kernel FUSE test when `/dev/fuse`, libfuse, and `fusermount3` are
available:

```shell
KODEX_FUSE_E2E=1 uv run test_kodex_session_fuse.py
```

## Boundary

A read-only SQL projection is small. A drop-in writable implementation is not:
it must translate temporary-file creation, atomic rename and replace, timeline
revert, lease updates, archive and delete operations, and cache invalidation
into database transactions. The preferred production direction to evaluate is
a direct SQLite `KodexAgentStorage` and `KodexSessionRepository`, with FUSE kept
as a compatibility, inspection, or export view.

The generic flat view favors a configurable proof over lookup performance.
SQLite currently scans the projected unions for a path lookup. A production
mount over a large repository needs either an indexed materialized path catalog
or path-aware prepared queries before performance claims can be made.

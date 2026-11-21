"""Measure the catalog query against a disposable, synthetic current database.

Run: uv run --no-project python scripts/measure-session-catalog.py --scratch-parent /path/on/disk
"""

import argparse
from contextlib import closing
from pathlib import Path
import re
import signal
import sqlite3
from statistics import median
from tempfile import TemporaryDirectory
from time import perf_counter


ROOT = Path(__file__).resolve().parents[1]
STORAGE = ROOT / "agent-storage/sqlite/src/specMain/kotlin/io/github/stream29/kodex/agentstorage/sqlite/SqliteSchema.kt"
REPOSITORY = ROOT / (
    "agent-session/sqlite/src/commonMain/kotlin/io/github/stream29/kodex/agentsession/sqlite/SqliteKodexSessionRepository.kt"
)


def literal(file, name):
    match = re.search(rf'\b{name}(?:: String)? = """(.*?)"""', file.read_text(), re.DOTALL)
    if not match:
        raise ValueError(f"Could not find {name} in {file}")
    return match[1]


def current_schema():
    source = STORAGE.read_text()
    match = re.search(r'\bSqliteSchema: String = """(.*?)""" \+ AgentHistorySchema \+ """(.*?)"""', source, re.DOTALL)
    if not match:
        raise ValueError(f"Could not find SqliteSchema composition in {STORAGE}")
    return match[1] + literal(STORAGE, "AgentHistorySchema") + match[2]


def seed(path, roots, positions):
    with closing(sqlite3.connect(path)) as db:
        # These tuning settings are for fixture generation only.
        db.execute("PRAGMA journal_mode = OFF")
        db.execute("PRAGMA synchronous = OFF")
        db.execute("PRAGMA cache_size = -8192")
        db.executescript(current_schema())
        with db:
            db.executemany("INSERT INTO sessions VALUES (?)", (
                (session,) for session in range(roots)
            ))
            db.executemany("INSERT INTO agents(agent_id,session_id,completed) VALUES (?, ?, ?)", (
                (session + 10000, session, int(session % 7 == 0)) for session in range(roots)
            ))
            db.executemany("INSERT INTO session_catalog_view VALUES (?, ?, ?, ?, ?)", (
                (session + 10000, None, None, None, None) if session % 31 == 0 else
                (session + 10000, f"s{session}", f"/fixture/{session % 75}", None, "2026-01-28T00:00:00Z")
                for session in range(roots)
            ))
        for session in range(roots):
            if session % 31 == 0:  # Uninitialized roots.
                continue
            agent = session + 10000
            with db:
                db.executemany("INSERT INTO agent_commits VALUES (?, ?, ?, ?, ?)", (
                    (
                        agent, "settings" if index % 19 == 0 else "user_message", index,
                        f'{{"name":"s{session}","cwd":"/fixture/{session % 75}","text":"{"x" * 320}"}}'
                        if index % 19 == 0 else '{"text":"' + "x" * (3072 if index % 31 == 0 else 160) + '"}',
                        f"2026-01-{(index % 28) + 1:02}T00:00:00Z",
                    )
                    for index in range(1 if session % 37 == 0 else 0, positions)
                ))
        return db.execute("SELECT count(*) FROM agent_commits").fetchone()[0]


def measure(path, label, sql, rounds):
    def fetch(db):
        started = perf_counter()
        rows = db.execute(sql).fetchall()
        return rows, (perf_counter() - started) * 1000

    with closing(sqlite3.connect(f"{path.as_uri()}?mode=ro", uri=True)) as db:
        db.execute("PRAGMA cache_size = -8192")
        rows, first = fetch(db)
        warm = []
        for _ in range(rounds):
            same, elapsed = fetch(db)
            assert same == rows
            warm.append(elapsed)
        details = [row[-1] for row in db.execute(f"EXPLAIN QUERY PLAN {sql}")]
        # Auth callback observes the real relation names; plan aliases can hide history reads.
        accessed = set()

        def authorize(operation, table, _column, _schema, _source):
            if operation == sqlite3.SQLITE_READ:
                accessed.add(table)
            return sqlite3.SQLITE_OK

        db.set_authorizer(authorize)
        assert db.execute(sql).fetchall() == rows
        print(f"{label}: rows={len(rows)} first={first:.3f}ms "
              f"warm median={median(warm):.3f}ms range={min(warm):.3f}–{max(warm):.3f}ms "
              f"reads={','.join(sorted(accessed))}")
        print("  " + "; ".join(details))
        return rows, accessed


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--scratch-parent", type=Path, required=True)
    args.add_argument("--roots", type=int, default=1500)
    args.add_argument("--positions", type=int, default=1575)
    args.add_argument("--rounds", type=int, default=5)
    options = args.parse_args()
    with TemporaryDirectory(prefix="kodex-catalog-bench-", dir=options.scratch_parent) as folder:
        database = Path(folder).resolve() / "synthetic.db"
        count = seed(database, options.roots, options.positions)
        print(f"SQLite {sqlite3.sqlite_version}; {options.roots} roots, {count:,} commits, "
              f"{database.stat().st_size / 2**20:.1f} MiB; first = fresh connection (OS cache not evicted)")
        for include_archived in (False, True):
            label = f"includeArchived={str(include_archived).lower()}"
            condition = "" if include_archived else " AND root.completed = 0 "
            sql = literal(REPOSITORY, "RootCatalog") + condition + " ORDER BY root.session_id"
            rows, reads = measure(database, label, sql, options.rounds)
            expected = options.roots if include_archived else sum(session % 7 != 0 for session in range(options.roots))
            assert len(rows) == expected
            assert reads == {"agents", "session_catalog_view"}


if __name__ == "__main__":
    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    main()

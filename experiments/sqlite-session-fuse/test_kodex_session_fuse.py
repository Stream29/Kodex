# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "fusepy==3.0.1",
# ]
# ///

from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import kodex_session_fuse

SCRIPT = Path(__file__).with_name("kodex_session_fuse.py")
FUSE_E2E_ENABLED = (
    os.environ.get("KODEX_FUSE_E2E") == "1"
    and Path("/dev/fuse").exists()
    and shutil.which("fusermount3") is not None
)


def _write_agent(
    directory: Path,
    entries: dict[str, dict[int, str]],
    *,
    archived: bool = False,
) -> None:
    directory.mkdir(parents=True)
    for timeline in kodex_session_fuse.TIMELINES:
        timeline_directory = directory / timeline
        timeline_directory.mkdir()
        for state_index, payload in entries.get(timeline, {}).items():
            (timeline_directory / f"{state_index}.json").write_bytes(
                payload.encode("utf-8")
            )
        (timeline_directory / "latest.json").write_text("999", encoding="utf-8")
    (directory / "subagents").mkdir()
    (directory / "lock.json").write_text('{"runtime":"ignored"}', encoding="utf-8")
    if archived:
        (directory / "archive.mark").touch()


class SqliteSessionFuseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.sessions = self.root / "source-sessions"
        self.sessions.mkdir()
        self.database = self.root / "sessions.sqlite3"

        root_agent = self.sessions / "7"
        _write_agent(
            root_agent,
            {
                "compaction": {0: '{"windowId":"root-window"}'},
                "settings": {
                    0: '{"threadName":"old","cwd":"/old","model":"old-model"}',
                    4: (
                        '{"threadName":"root","cwd":"/workspace","model":"test-model"}'
                    ),
                },
                "timestamp": {0: '"2026-08-27T00:00:00Z"'},
                "token-count": {0: "42"},
                "stable": {3: '{\r\n  "type": "assistant"\r\n}'},
                "unstable": {3: '[{"type":"tool"}]'},
            },
            archived=True,
        )
        _write_agent(
            root_agent / "subagents" / "2",
            {
                "settings": {
                    1: (
                        '{"threadName":"child","cwd":"/workspace","model":"test-model"}'
                    )
                },
                "timestamp": {1: '"2026-08-27T00:01:00Z"'},
                "token-count": {1: "5"},
            },
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _import(self) -> kodex_session_fuse.ImportStats:
        return kodex_session_fuse.import_sessions(self.sessions, self.database)

    def test_import_projects_current_layout_and_derived_heads(self) -> None:
        stats = self._import()
        self.assertEqual(stats.agents, 2)
        self.assertEqual(stats.timeline_entries, 10)

        with sqlite3.connect(self.database) as connection:
            connection.row_factory = sqlite3.Row
            nodes = {
                row["path"]: row
                for row in connection.execute(
                    "SELECT path, kind, content FROM fuse_nodes"
                )
            }

            self.assertIn("/", nodes)
            self.assertIn("/sessions/7/subagents/2/unstable/latest.json", nodes)
            self.assertIn("/sessions/7/archive.mark", nodes)
            self.assertNotIn("/sessions/7/lock.json", nodes)
            self.assertEqual(
                nodes["/sessions/7/settings/latest.json"]["content"],
                "4",
            )
            self.assertEqual(
                nodes["/sessions/7/subagents/2/unstable/latest.json"]["content"],
                "-1",
            )
            self.assertEqual(
                nodes["/sessions/7/stable/3.json"]["content"],
                '{\r\n  "type": "assistant"\r\n}',
            )

            summary = connection.execute(
                """
                SELECT thread_name, cwd, model, latest_token_count
                FROM agent_summary
                WHERE path = '/sessions/7'
                """
            ).fetchone()
            self.assertEqual(
                tuple(summary),
                ("root", "/workspace", "test-model", 42),
            )

            root_agent_id = connection.execute(
                "SELECT agent_id FROM agent_paths WHERE path = '/sessions/7'"
            ).fetchone()[0]
            connection.execute(
                """
                INSERT INTO stable_entries(
                    agent_id,
                    state_index,
                    payload,
                    mtime_ns
                )
                VALUES (?, 9, '{"type":"live-sql"}', ?)
                """,
                (root_agent_id, time.time_ns()),
            )
            connection.commit()
            latest = connection.execute(
                """
                SELECT content
                FROM fuse_nodes
                WHERE path = '/sessions/7/stable/latest.json'
                """
            ).fetchone()[0]
            self.assertEqual(latest, "9")

            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    """
                    INSERT INTO stable_entries(
                        agent_id,
                        state_index,
                        payload,
                        mtime_ns
                    )
                    VALUES (?, 10, 'not-json', ?)
                    """,
                    (root_agent_id, time.time_ns()),
                )
            connection.rollback()
            connection.execute(
                """
                CREATE VIEW stable_only_nodes AS
                SELECT *
                FROM fuse_nodes
                WHERE name <> 'unstable'
                    AND path NOT LIKE '%/unstable/%'
                """
            )
            self.assertEqual(
                connection.execute(
                    """
                    SELECT COUNT(*)
                    FROM stable_only_nodes
                    WHERE path LIKE '%/unstable%'
                    """
                ).fetchone()[0],
                0,
            )

        kodex_session_fuse.validate_database(
            self.database,
            "fuse_nodes",
            deep=True,
        )
        kodex_session_fuse.validate_database(
            self.database,
            "stable_only_nodes",
            deep=True,
        )

    def test_failed_import_leaves_no_database_or_temporary_files(self) -> None:
        shutil.rmtree(self.sessions / "7" / "settings")

        with self.assertRaises(kodex_session_fuse.LayoutError):
            self._import()

        self.assertFalse(self.database.exists())
        leftovers = list(self.root.glob(f".{self.database.name}.*.tmp*"))
        self.assertEqual(leftovers, [])

    @unittest.skipUnless(
        FUSE_E2E_ENABLED,
        "set KODEX_FUSE_E2E=1 on a host with /dev/fuse",
    )
    def test_fuse_mount_reflects_committed_sql_changes(self) -> None:
        self._import()
        mountpoint = self.root / "mount"
        mountpoint.mkdir()
        process = subprocess.Popen(
            [
                sys.executable,
                str(SCRIPT),
                "mount",
                str(self.database),
                str(mountpoint),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            projected_entry = mountpoint / "sessions/7/stable/3.json"
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    stdout, stderr = process.communicate()
                    self.fail(
                        "FUSE process exited before mounting:\n"
                        f"stdout: {stdout}\nstderr: {stderr}"
                    )
                try:
                    if projected_entry.read_bytes() == (
                        b'{\r\n  "type": "assistant"\r\n}'
                    ):
                        break
                except FileNotFoundError:
                    pass
                time.sleep(0.05)
            else:
                self.fail("FUSE projection did not become readable")

            projected_latest = mountpoint / "sessions/7/stable/latest.json"
            self.assertEqual(projected_latest.read_text(encoding="utf-8"), "3")

            with sqlite3.connect(self.database, timeout=5) as connection:
                root_agent_id = connection.execute(
                    "SELECT agent_id FROM agent_paths WHERE path = '/sessions/7'"
                ).fetchone()[0]
                connection.execute(
                    """
                    UPDATE stable_entries
                    SET payload = '{"type":"updated-in-place"}',
                        mtime_ns = ?
                    WHERE agent_id = ?
                        AND state_index = 3
                    """,
                    (time.time_ns(), root_agent_id),
                )
                connection.execute(
                    """
                    INSERT INTO stable_entries(
                        agent_id,
                        state_index,
                        payload,
                        mtime_ns
                    )
                    VALUES (?, 12, '{"type":"mounted-update"}', ?)
                    """,
                    (root_agent_id, time.time_ns()),
                )
                connection.commit()

            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if projected_entry.read_text(encoding="utf-8") == (
                    '{"type":"updated-in-place"}'
                ):
                    break
                time.sleep(0.05)
            else:
                self.fail("updated SQL payload remained cached by FUSE")

            projected_update = mountpoint / "sessions/7/stable/12.json"
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    if projected_update.read_text(encoding="utf-8") == (
                        '{"type":"mounted-update"}'
                    ):
                        break
                except FileNotFoundError:
                    pass
                time.sleep(0.05)
            else:
                self.fail("committed SQL change did not appear through FUSE")

            self.assertEqual(
                projected_latest.read_text(encoding="utf-8"),
                "12",
            )
        finally:
            subprocess.run(
                ["fusermount3", "-u", str(mountpoint)],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=5)

        stdout, stderr = process.communicate()
        self.assertEqual(
            process.returncode,
            0,
            f"stdout: {stdout}\nstderr: {stderr}",
        )


if __name__ == "__main__":
    unittest.main()

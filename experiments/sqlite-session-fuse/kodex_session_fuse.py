# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "fusepy==3.0.1",
# ]
# ///

from __future__ import annotations

import argparse
import errno
import json
import os
import re
import sqlite3
import stat
import sys
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

TIMELINES = {
    "compaction": "compaction_entries",
    "settings": "settings_entries",
    "timestamp": "timestamp_entries",
    "token-count": "token_count_entries",
    "stable": "stable_entries",
    "unstable": "unstable_entries",
}
VIEW_NAME_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
INDEX_FILE_PATTERN = re.compile(r"([0-9]+)\.json")
REQUIRED_VIEW_COLUMNS = {
    "path",
    "parent_path",
    "name",
    "kind",
    "content",
    "mtime_ns",
}


class LayoutError(ValueError):
    pass


@dataclass
class ImportStats:
    agents: int = 0
    timeline_entries: int = 0


def _schema_path() -> Path:
    return Path(__file__).with_name("schema.sql")


def _quoted_identifier(name: str) -> str:
    if not VIEW_NAME_PATTERN.fullmatch(name):
        raise ValueError(f"invalid SQL view name: {name!r}")
    return f'"{name}"'


def _readonly_connection(database: Path) -> sqlite3.Connection:
    encoded_path = quote(str(database.resolve()), safe="/")
    connection = sqlite3.connect(
        f"file:{encoded_path}?mode=ro",
        uri=True,
        timeout=5,
    )
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only = ON")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


def _parse_index(name: str, description: str) -> int:
    if not name.isascii() or not name.isdecimal():
        raise LayoutError(f"{description} must be a decimal index: {name!r}")
    index = int(name)
    if str(index) != name:
        raise LayoutError(f"{description} is not canonical: {name!r}")
    if index > 9_223_372_036_854_775_807:
        raise LayoutError(f"{description} exceeds SQLite INTEGER range: {name!r}")
    return index


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant: {value}")


def _read_json_payload(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise LayoutError(f"timeline entry must be a regular file: {path}")
    try:
        payload = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError as error:
        raise LayoutError(f"timeline entry is not UTF-8: {path}") from error
    try:
        json.loads(payload, parse_constant=_reject_json_constant)
    except (json.JSONDecodeError, ValueError) as error:
        raise LayoutError(
            f"timeline entry is not valid JSON: {path}: {error}"
        ) from error
    return payload


def _sorted_numeric_directories(
    directory: Path, description: str
) -> list[tuple[int, Path]]:
    result: list[tuple[int, Path]] = []
    for child in directory.iterdir():
        if child.name.startswith("."):
            continue
        if child.is_symlink() or not child.is_dir():
            raise LayoutError(f"unexpected entry in {description}: {child}")
        index = _parse_index(child.name, description)
        result.append((index, child))
    return sorted(result, key=lambda item: item[0])


def _validate_agent_children(directory: Path, is_root_agent: bool) -> None:
    allowed = set(TIMELINES) | {"subagents", "lock.json"}
    if is_root_agent:
        allowed.add("archive.mark")
    for child in directory.iterdir():
        if child.name.startswith("."):
            continue
        if child.name not in allowed:
            raise LayoutError(f"unexpected agent entry: {child}")


def _import_timeline(
    connection: sqlite3.Connection,
    agent_id: int,
    directory: Path,
    table_name: str,
    stats: ImportStats,
) -> None:
    if directory.is_symlink() or not directory.is_dir():
        raise LayoutError(f"missing timeline directory: {directory}")

    entries: list[tuple[int, Path]] = []
    for child in directory.iterdir():
        if child.name.startswith(".") or child.name == "latest.json":
            continue
        match = INDEX_FILE_PATTERN.fullmatch(child.name)
        if match is None:
            raise LayoutError(f"unexpected timeline entry: {child}")
        index = _parse_index(match.group(1), "timeline entry index")
        entries.append((index, child))

    for state_index, entry_path in sorted(entries, key=lambda item: item[0]):
        payload = _read_json_payload(entry_path)
        connection.execute(
            f"""
            INSERT INTO {table_name}(agent_id, state_index, payload, mtime_ns)
            VALUES (?, ?, ?, ?)
            """,
            (agent_id, state_index, payload, entry_path.stat().st_mtime_ns),
        )
        stats.timeline_entries += 1


def _import_agent(
    connection: sqlite3.Connection,
    directory: Path,
    entry_index: int,
    parent_agent_id: int | None,
    stats: ImportStats,
) -> None:
    is_root_agent = parent_agent_id is None
    _validate_agent_children(directory, is_root_agent)

    archive_marker = directory / "archive.mark"
    if archive_marker.exists() and (
        archive_marker.is_symlink() or not archive_marker.is_file()
    ):
        raise LayoutError(f"archive marker must be a regular file: {archive_marker}")

    cursor = connection.execute(
        """
        INSERT INTO agents(parent_agent_id, entry_index, archived, mtime_ns)
        VALUES (?, ?, ?, ?)
        """,
        (
            parent_agent_id,
            entry_index,
            int(is_root_agent and archive_marker.exists()),
            directory.stat().st_mtime_ns,
        ),
    )
    agent_id = cursor.lastrowid
    if agent_id is None:
        raise RuntimeError(f"SQLite did not return an agent id for {directory}")
    stats.agents += 1

    for timeline_name, table_name in TIMELINES.items():
        _import_timeline(
            connection,
            agent_id,
            directory / timeline_name,
            table_name,
            stats,
        )

    subagents = directory / "subagents"
    if subagents.is_symlink() or not subagents.is_dir():
        raise LayoutError(f"missing subagents directory: {subagents}")
    for child_index, child_directory in _sorted_numeric_directories(
        subagents,
        "subagent index",
    ):
        _import_agent(
            connection,
            child_directory,
            child_index,
            agent_id,
            stats,
        )


def validate_projection(
    connection: sqlite3.Connection,
    view_name: str = "fuse_nodes",
    *,
    deep: bool = False,
) -> None:
    quoted_view = _quoted_identifier(view_name)
    columns = {
        row["name"]
        for row in connection.execute(f"PRAGMA table_info({quoted_view})").fetchall()
    }
    missing_columns = REQUIRED_VIEW_COLUMNS - columns
    if missing_columns:
        missing = ", ".join(sorted(missing_columns))
        raise ValueError(f"SQL view {view_name!r} is missing columns: {missing}")

    root_count = connection.execute(
        f"SELECT COUNT(*) FROM {quoted_view} WHERE path = '/'"
    ).fetchone()[0]
    if root_count != 1:
        raise ValueError(
            f"SQL view {view_name!r} must contain exactly one root node, found "
            f"{root_count}"
        )

    invalid_kind = connection.execute(
        f"""
        SELECT path
        FROM {quoted_view}
        WHERE kind NOT IN ('directory', 'file')
        LIMIT 1
        """
    ).fetchone()
    if invalid_kind is not None:
        raise ValueError(
            f"SQL view {view_name!r} has an invalid node kind at "
            f"{invalid_kind['path']!r}"
        )

    if not deep:
        return

    duplicate = connection.execute(
        f"""
        SELECT path, COUNT(*) AS count
        FROM {quoted_view}
        GROUP BY path
        HAVING count > 1
        LIMIT 1
        """
    ).fetchone()
    if duplicate is not None:
        raise ValueError(
            f"SQL view {view_name!r} has duplicate path {duplicate['path']!r}"
        )

    orphan = connection.execute(
        f"""
        SELECT child.path
        FROM {quoted_view} AS child
        LEFT JOIN {quoted_view} AS parent
            ON parent.path = child.parent_path
        WHERE child.path <> '/'
            AND (parent.path IS NULL OR parent.kind <> 'directory')
        LIMIT 1
        """
    ).fetchone()
    if orphan is not None:
        raise ValueError(
            f"SQL view {view_name!r} has a missing or non-directory parent for "
            f"{orphan['path']!r}"
        )


def import_sessions(
    source: Path, database: Path, *, replace: bool = False
) -> ImportStats:
    source = source.expanduser()
    if source.is_symlink():
        raise LayoutError(f"session source must not be a symbolic link: {source}")
    source = source.resolve()
    database = database.expanduser().resolve()
    if not source.is_dir():
        raise LayoutError(f"session source must be a directory: {source}")
    if not database.parent.is_dir():
        raise FileNotFoundError(f"database parent does not exist: {database.parent}")
    if database.exists() and not replace:
        raise FileExistsError(f"database already exists: {database}")

    if replace:
        active_sidecars = [
            sidecar
            for suffix in ("-wal", "-shm", "-journal")
            if (sidecar := Path(f"{database}{suffix}")).exists()
        ]
        if active_sidecars:
            names = ", ".join(str(path) for path in active_sidecars)
            raise RuntimeError(
                "refusing to replace a database with SQLite sidecars; close all "
                f"users first: {names}"
            )

    temporary = database.parent / f".{database.name}.{uuid.uuid4().hex}.tmp"
    temporary_sidecars = [
        Path(f"{temporary}-wal"),
        Path(f"{temporary}-shm"),
        Path(f"{temporary}-journal"),
    ]
    connection: sqlite3.Connection | None = None
    stats = ImportStats()
    try:
        connection = sqlite3.connect(temporary, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.executescript(_schema_path().read_text(encoding="utf-8"))
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("BEGIN IMMEDIATE")
        for entry_index, directory in _sorted_numeric_directories(
            source,
            "root session index",
        ):
            _import_agent(
                connection,
                directory,
                entry_index,
                None,
                stats,
            )
        connection.commit()
        validate_projection(connection, deep=True)
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        connection.close()
        connection = None

        if database.exists() and not replace:
            raise FileExistsError(f"database already exists: {database}")
        os.replace(temporary, database)
        return stats
    except BaseException:
        if connection is not None:
            connection.rollback()
        raise
    finally:
        if connection is not None:
            connection.close()
        if temporary.exists():
            temporary.unlink()
        for sidecar in temporary_sidecars:
            if sidecar.exists():
                sidecar.unlink()


def validate_database(database: Path, view_name: str, *, deep: bool) -> None:
    database = database.expanduser().resolve()
    with _readonly_connection(database) as connection:
        validate_projection(connection, view_name, deep=deep)


def mount_database(database: Path, mountpoint: Path, view_name: str) -> None:
    try:
        from fuse import FUSE, FuseOSError, Operations
    except ImportError as error:
        raise RuntimeError(
            "fusepy is unavailable; run this file with `uv run`"
        ) from error

    database = database.expanduser().resolve()
    mountpoint = mountpoint.expanduser().resolve()
    if not database.is_file():
        raise FileNotFoundError(f"database does not exist: {database}")
    if not mountpoint.is_dir():
        raise FileNotFoundError(f"mountpoint does not exist: {mountpoint}")
    if any(mountpoint.iterdir()):
        raise ValueError(f"mountpoint must be empty: {mountpoint}")

    connection = _readonly_connection(database)
    validate_projection(connection, view_name)
    quoted_view = _quoted_identifier(view_name)

    class SqliteViewOperations(Operations):
        def __init__(self) -> None:
            self.connection = connection

        def _node(self, path: str) -> sqlite3.Row:
            try:
                node = self.connection.execute(
                    f"""
                    SELECT path, parent_path, name, kind, content, mtime_ns
                    FROM {quoted_view}
                    WHERE path = ?
                    """,
                    (path,),
                ).fetchone()
            except sqlite3.Error as error:
                raise FuseOSError(errno.EIO) from error
            if node is None:
                raise FuseOSError(errno.ENOENT)
            return node

        @staticmethod
        def _content(node: sqlite3.Row) -> bytes:
            value = node["content"]
            if value is None:
                return b""
            if isinstance(value, bytes):
                return value
            return str(value).encode("utf-8")

        def access(self, path: str, mode: int) -> int:
            self._node(path)
            if mode & os.W_OK:
                raise FuseOSError(errno.EROFS)
            return 0

        def getattr(self, path: str, fh: int | None = None) -> dict[str, Any]:
            del fh
            node = self._node(path)
            is_directory = node["kind"] == "directory"
            content = b"" if is_directory else self._content(node)
            modified_at = int(node["mtime_ns"]) / 1_000_000_000
            return {
                "st_mode": (
                    stat.S_IFDIR | 0o555 if is_directory else stat.S_IFREG | 0o444
                ),
                "st_nlink": 2 if is_directory else 1,
                "st_size": len(content),
                "st_uid": os.getuid(),
                "st_gid": os.getgid(),
                "st_atime": modified_at,
                "st_mtime": modified_at,
                "st_ctime": modified_at,
            }

        def readdir(self, path: str, fh: int) -> list[str]:
            del fh
            node = self._node(path)
            if node["kind"] != "directory":
                raise FuseOSError(errno.ENOTDIR)
            try:
                names = [
                    row["name"]
                    for row in self.connection.execute(
                        f"""
                        SELECT name
                        FROM {quoted_view}
                        WHERE parent_path = ?
                        ORDER BY name
                        """,
                        (path,),
                    )
                ]
            except sqlite3.Error as error:
                raise FuseOSError(errno.EIO) from error
            return [".", "..", *names]

        def open(self, path: str, flags: int) -> int:
            write_flags = (
                os.O_WRONLY
                | os.O_RDWR
                | os.O_APPEND
                | os.O_CREAT
                | os.O_EXCL
                | os.O_TRUNC
            )
            if flags & write_flags:
                raise FuseOSError(errno.EROFS)
            node = self._node(path)
            if node["kind"] == "directory":
                raise FuseOSError(errno.EISDIR)
            return 0

        def read(self, path: str, size: int, offset: int, fh: int) -> bytes:
            del fh
            content = self._content(self._node(path))
            return content[offset : offset + size]

        def flush(self, path: str, fh: int) -> int:
            del path, fh
            return 0

        def release(self, path: str, fh: int) -> int:
            del path, fh
            return 0

        def statfs(self, path: str) -> dict[str, int]:
            self._node(path)
            return {
                "f_bsize": 4096,
                "f_frsize": 4096,
                "f_blocks": 1,
                "f_bfree": 0,
                "f_bavail": 0,
                "f_files": 1,
                "f_ffree": 0,
                "f_favail": 0,
                "f_flag": getattr(os, "ST_RDONLY", 1),
                "f_namemax": 255,
            }

        def destroy(self, path: str) -> None:
            del path
            self.connection.close()

    FUSE(
        SqliteViewOperations(),
        str(mountpoint),
        attr_timeout=0,
        direct_io=True,
        entry_timeout=0,
        foreground=True,
        negative_timeout=0,
        nothreads=True,
        ro=True,
        fsname="kodex-sqlite",
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Project a SQLite Kodex session database through read-only FUSE."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    import_parser = subparsers.add_parser(
        "import",
        help="Import an existing Kodex sessions directory into a new database.",
    )
    import_parser.add_argument("source", type=Path)
    import_parser.add_argument("database", type=Path)
    import_parser.add_argument(
        "--replace",
        action="store_true",
        help="Atomically replace an inactive database.",
    )

    validate_parser = subparsers.add_parser(
        "validate",
        help="Validate the SQL-to-filesystem view contract.",
    )
    validate_parser.add_argument("database", type=Path)
    validate_parser.add_argument("--view", default="fuse_nodes")
    validate_parser.add_argument(
        "--deep",
        action="store_true",
        help="Also scan for duplicate paths and invalid parent relationships.",
    )

    mount_parser = subparsers.add_parser(
        "mount",
        help="Mount a SQL node view as a read-only filesystem.",
    )
    mount_parser.add_argument("database", type=Path)
    mount_parser.add_argument("mountpoint", type=Path)
    mount_parser.add_argument("--view", default="fuse_nodes")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "import":
            stats = import_sessions(
                arguments.source,
                arguments.database,
                replace=arguments.replace,
            )
            print(
                f"Imported {stats.agents} agents and "
                f"{stats.timeline_entries} timeline entries into "
                f"{arguments.database.expanduser().resolve()}"
            )
        elif arguments.command == "validate":
            validate_database(
                arguments.database,
                arguments.view,
                deep=arguments.deep,
            )
            print(f"SQL view {arguments.view!r} is valid")
        elif arguments.command == "mount":
            mount_database(
                arguments.database,
                arguments.mountpoint,
                arguments.view,
            )
        else:
            parser.error(f"unknown command: {arguments.command}")
    except KeyboardInterrupt:
        return 130
    except (LayoutError, OSError, RuntimeError, sqlite3.Error, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

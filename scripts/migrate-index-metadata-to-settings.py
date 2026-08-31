#!/usr/bin/env -S uv run python

"""Move index-owned turn/window metadata into settings snapshots.

The application never invokes this script. It is intended for the local
one-time migration from the previous index/work schemas.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any


TIMELINES = (
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "unstable",
)
# Kotlin serialization keeps this property in camelCase, just like the
# persisted `KodexAgentSettings` and clean-model payloads.
TURN_ID = "turnId"
TURN_MARKER = "turn_marker"
COMPACTION_POINT = "compaction_point"
CONTEXT_COMPACTION = "context_compaction"
WINDOW_NUMBER = "windowNumber"
FIRST_WINDOW_ID = "firstWindowId"
PREVIOUS_WINDOW_ID = "previousWindowId"
WINDOW_ID = "windowId"
WINDOW_FIELDS = (
    WINDOW_NUMBER,
    FIRST_WINDOW_ID,
    PREVIOUS_WINDOW_ID,
    WINDOW_ID,
)
REQUIRED_WINDOW_FIELDS = (
    WINDOW_NUMBER,
    FIRST_WINDOW_ID,
    WINDOW_ID,
)


@dataclass(frozen=True)
class SessionRewrite:
    session: Path
    index: dict[int, bytes]
    settings: dict[int, bytes]
    before: dict[Path, bytes]


@dataclass(frozen=True)
class ScanResult:
    sessions: int
    already_migrated: int
    pending: tuple[SessionRewrite, ...]


def parse_json(path: Path, content: bytes) -> Any:
    try:
        return json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{path}: invalid JSON") from error


def encode_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()


def numeric_entries(directory: Path) -> dict[int, bytes]:
    if not directory.is_dir():
        raise ValueError(f"{directory}: timeline directory does not exist")
    entries: dict[int, bytes] = {}
    for path in directory.glob("*.json"):
        if path.stem == "latest" or not path.stem.isdigit():
            continue
        index = int(path.stem)
        if index in entries:
            raise ValueError(f"{directory}: duplicate entry {index}")
        entries[index] = path.read_bytes()
    return entries


def validate_latest_pointer(directory: Path, entries: dict[int, bytes]) -> None:
    pointer = directory / "latest.json"
    if not pointer.is_file():
        raise ValueError(f"{pointer}: missing latest pointer")
    value = parse_json(pointer, pointer.read_bytes())
    expected = max(entries, default=-1)
    if value != expected:
        raise ValueError(f"{pointer}: expected {expected}, got {value}")


def load_session(session: Path) -> dict[str, dict[int, bytes]]:
    result = {
        name: numeric_entries(session / name)
        for name in TIMELINES
    }
    for name, entries in result.items():
        validate_latest_pointer(session / name, entries)
    return result


def object_at(session: Path, timeline: str, index: int, content: bytes) -> dict:
    value = parse_json(session / timeline / f"{index}.json", content)
    if not isinstance(value, dict):
        raise TypeError(f"{session}/{timeline}/{index}.json: expected an object")
    return value


def validate_compaction_pairs(
    session: Path,
    index_entries: dict[int, bytes],
    work_entries: dict[int, bytes],
) -> None:
    for index, content in index_entries.items():
        value = object_at(session, "index", index, content)
        if value.get("type") != COMPACTION_POINT or index == 0:
            continue
        output = work_entries.get(index + 1)
        if output is None:
            raise ValueError(
                f"{session}: compaction point {index} has no work output at {index + 1}"
            )
        output_value = object_at(session, "work", index + 1, output)
        if output_value.get("type") != CONTEXT_COMPACTION:
            raise ValueError(
                f"{session}: compaction point {index} is not followed by context compaction"
            )


def legacy_turn_id(value: dict, path: Path) -> str | None:
    turn_id = value.get(TURN_ID)
    if turn_id is None:
        return None
    if not isinstance(turn_id, str) or not turn_id:
        raise ValueError(f"{path}: {TURN_ID} must be a non-empty string")
    return turn_id


def compaction_lineage(
    value: dict,
    path: Path,
    *,
    allow_empty_placeholder: bool = False,
) -> dict[str, Any] | None:
    present = [field in value for field in WINDOW_FIELDS]
    if not any(present):
        return None
    missing_required = [
        field for field in REQUIRED_WINDOW_FIELDS if field not in value
    ]
    if missing_required:
        missing = ", ".join(
            missing_required
        )
        raise ValueError(f"{path}: incomplete compaction lineage; missing {missing}")

    window_number = value[WINDOW_NUMBER]
    first_window_id = value[FIRST_WINDOW_ID]
    previous_window_id = value.get(PREVIOUS_WINDOW_ID)
    window_id = value[WINDOW_ID]
    if (
        allow_empty_placeholder
        and window_number == 0
        and first_window_id == ""
        and previous_window_id is None
        and window_id == ""
    ):
        return None
    if (
        isinstance(window_number, bool)
        or not isinstance(window_number, int)
        or window_number < 0
    ):
        raise ValueError(f"{path}: {WINDOW_NUMBER} must be a non-negative integer")
    if not isinstance(first_window_id, str) or not first_window_id:
        raise ValueError(f"{path}: {FIRST_WINDOW_ID} must be a non-empty string")
    if previous_window_id is not None and (
        not isinstance(previous_window_id, str) or not previous_window_id
    ):
        raise ValueError(
            f"{path}: {PREVIOUS_WINDOW_ID} must be a non-empty string or null"
        )
    if not isinstance(window_id, str) or not window_id:
        raise ValueError(f"{path}: {WINDOW_ID} must be a non-empty string")
    return {
        WINDOW_NUMBER: window_number,
        FIRST_WINDOW_ID: first_window_id,
        PREVIOUS_WINDOW_ID: previous_window_id,
        WINDOW_ID: window_id,
    }


def apply_lineage(value: dict[str, Any], lineage: dict[str, Any]) -> None:
    value[WINDOW_NUMBER] = lineage[WINDOW_NUMBER]
    value[FIRST_WINDOW_ID] = lineage[FIRST_WINDOW_ID]
    previous_window_id = lineage[PREVIOUS_WINDOW_ID]
    if previous_window_id is None:
        value.pop(PREVIOUS_WINDOW_ID, None)
    else:
        value[PREVIOUS_WINDOW_ID] = previous_window_id
    value[WINDOW_ID] = lineage[WINDOW_ID]


def floor_index(indexes: list[int], upper: int) -> int | None:
    candidate = None
    for index in indexes:
        if index > upper:
            break
        candidate = index
    return candidate


def validate_lineage_chain(session: Path, lineages: dict[int, dict[str, Any]]) -> None:
    if 0 not in lineages:
        raise ValueError(f"{session}: initial compaction point must be stored at index 0")
    previous: dict[str, Any] | None = None
    for index, lineage in sorted(lineages.items()):
        if previous is None:
            if lineage[WINDOW_NUMBER] != 0:
                raise ValueError(f"{session}: initial window number must be 0")
            if lineage[PREVIOUS_WINDOW_ID] is not None:
                raise ValueError(f"{session}: initial previous window id must be null")
            if lineage[FIRST_WINDOW_ID] != lineage[WINDOW_ID]:
                raise ValueError(
                    f"{session}: initial first window id must equal window id"
                )
        else:
            if lineage[WINDOW_NUMBER] != previous[WINDOW_NUMBER] + 1:
                raise ValueError(
                    f"{session}: compaction point {index} has a non-consecutive "
                    "window number"
                )
            if lineage[FIRST_WINDOW_ID] != previous[FIRST_WINDOW_ID]:
                raise ValueError(
                    f"{session}: compaction point {index} changes first window id"
                )
            if lineage[PREVIOUS_WINDOW_ID] != previous[WINDOW_ID]:
                raise ValueError(
                    f"{session}: compaction point {index} has the wrong previous "
                    "window id"
                )
        previous = lineage


def rewrite_session(session: Path) -> SessionRewrite | None:
    entries = load_session(session)
    validate_compaction_pairs(session, entries["index"], entries["work"])

    index_values = {
        index: object_at(session, "index", index, content)
        for index, content in entries["index"].items()
    }
    settings_values = {
        index: object_at(session, "settings", index, content)
        for index, content in entries["settings"].items()
    }
    marker_ids = {
        index: value.get(TURN_ID)
        for index, value in index_values.items()
        if value.get("type") == TURN_MARKER
    }
    for index, turn_id in marker_ids.items():
        if not isinstance(turn_id, str) or not turn_id:
            raise ValueError(
                f"{session}/index/{index}.json: marker turn_id must be non-empty"
            )

    point_values = {
        index: value
        for index, value in index_values.items()
        if value.get("type") == COMPACTION_POINT
    }
    if 0 not in point_values:
        raise ValueError(f"{session}: initial compaction point must be stored at index 0")

    settings_have_turn_ids = [
        TURN_ID in value for value in settings_values.values()
    ]
    if not marker_ids:
        if any(settings_have_turn_ids) and not all(settings_have_turn_ids):
            raise ValueError(
                f"{session}: settings contain only some turn_id values"
            )
        if not any(settings_have_turn_ids):
            raise ValueError(f"{session}: no turn markers or settings turn_id values")
        for index, value in settings_values.items():
            legacy_turn_id(value, session / "settings" / f"{index}.json")
        new_settings_values = {
            index: dict(value) for index, value in settings_values.items()
        }
    else:
        if 1 not in marker_ids:
            raise ValueError(f"{session}: initial turn marker must be stored at index 1")
        marker_indexes = sorted(marker_ids)
        setting_indexes = sorted(settings_values)
        new_settings_values: dict[int, dict[str, Any]] = {}
        for settings_index in setting_indexes:
            active_marker = floor_index(marker_indexes, settings_index)
            if active_marker is None:
                active_marker = 1
            value = dict(settings_values[settings_index])
            existing = legacy_turn_id(
                value,
                session / "settings" / f"{settings_index}.json",
            )
            if existing is not None and existing != marker_ids[active_marker]:
                raise ValueError(
                    f"{session}: settings turn_id disagrees at {settings_index}"
                )
            value[TURN_ID] = marker_ids[active_marker]
            new_settings_values[settings_index] = value

        for marker_index, turn_id in sorted(marker_ids.items()):
            base_index = floor_index(setting_indexes, marker_index)
            if base_index is None:
                raise ValueError(
                    f"{session}: no settings snapshot visible before marker "
                    f"{marker_index}"
                )
            base = dict(settings_values[base_index])
            base[TURN_ID] = turn_id
            new_settings_values[marker_index] = base

    point_lineages: dict[int, dict[str, Any]] = {}
    settings_indexes = sorted(new_settings_values)
    for point_index, point in sorted(point_values.items()):
        point_path = session / "index" / f"{point_index}.json"
        lineage = compaction_lineage(point, point_path)
        if lineage is None:
            settings_index = floor_index(settings_indexes, point_index)
            if settings_index is None:
                raise ValueError(
                    f"{session}: no settings snapshot visible at compaction point "
                    f"{point_index}"
                )
            lineage = compaction_lineage(
                new_settings_values[settings_index],
                session / "settings" / f"{settings_index}.json",
                allow_empty_placeholder=True,
            )
            if lineage is None:
                raise ValueError(
                    f"{session}: compaction point {point_index} has no lineage in "
                    "either index or settings"
                )
        point_lineages[point_index] = lineage

        base_index = floor_index(settings_indexes, point_index)
        if base_index is None:
            raise ValueError(
                f"{session}: no settings snapshot visible at compaction point "
                f"{point_index}"
            )
        point_settings = dict(new_settings_values[base_index])
        apply_lineage(point_settings, lineage)
        new_settings_values[point_index] = point_settings
        settings_indexes = sorted(new_settings_values)

    validate_lineage_chain(session, point_lineages)

    point_indexes = sorted(point_lineages)
    for settings_index, settings in sorted(new_settings_values.items()):
        point_index = floor_index(point_indexes, settings_index)
        if point_index is None:
            raise ValueError(
                f"{session}: settings snapshot {settings_index} precedes initial "
                "compaction point"
            )
        expected = point_lineages[point_index]
        existing = compaction_lineage(
            settings,
            session / "settings" / f"{settings_index}.json",
            allow_empty_placeholder=True,
        )
        if existing is not None and existing != expected:
            raise ValueError(
                f"{session}: settings lineage disagrees at {settings_index}"
            )
        apply_lineage(settings, expected)

    new_index = {
        index: (
            encode_json({"type": COMPACTION_POINT})
            if index in point_values
            else content
        )
        for index, content in entries["index"].items()
        if index not in marker_ids
    }
    if 0 not in new_index:
        raise ValueError(f"{session}: removing markers would remove index zero")
    new_settings = {
        index: encode_json(value)
        for index, value in new_settings_values.items()
    }
    if new_index == entries["index"] and new_settings == entries["settings"]:
        return None

    before = {
        path: path.read_bytes()
        for name in ("index", "settings")
        for path in (session / name).glob("*.json")
    }
    return SessionRewrite(session, new_index, new_settings, before)


def scan(sessions: Path) -> ScanResult:
    if not sessions.is_dir():
        raise ValueError(f"{sessions}: sessions directory does not exist")
    session_paths = sorted(
        path for path in sessions.iterdir()
        if path.is_dir() and path.name.isdigit()
    )
    pending: list[SessionRewrite] = []
    already_migrated = 0
    for session in session_paths:
        rewrite = rewrite_session(session)
        if rewrite is None:
            already_migrated += 1
        else:
            pending.append(rewrite)
    return ScanResult(
        sessions=len(session_paths),
        already_migrated=already_migrated,
        pending=tuple(pending),
    )


def live_kodex_pids() -> list[int]:
    proc = Path("/proc")
    if not proc.is_dir():
        return []
    result: list[int] = []
    for process in proc.iterdir():
        if not process.name.isdigit():
            continue
        try:
            if (process / "comm").read_text().strip() == "kodex-cli":
                result.append(int(process.name))
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            pass
    return sorted(result)


def fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_timeline(
    directory: Path,
    entries: dict[int, bytes],
) -> None:
    shutil.rmtree(directory)
    directory.mkdir()
    for index, content in sorted(entries.items()):
        (directory / f"{index}.json").write_bytes(content)
    (directory / "latest.json").write_text(str(max(entries)), encoding="utf-8")
    fsync_directory(directory)


def apply_rewrite(rewrite: SessionRewrite) -> None:
    for path, content in rewrite.before.items():
        if not path.is_file() or path.read_bytes() != content:
            raise RuntimeError(f"{path}: changed after preflight")

    stage = rewrite.session.with_name(
        f".{rewrite.session.name}.index-metadata-{uuid.uuid4().hex}.tmp"
    )
    backup = rewrite.session.with_name(
        f".{rewrite.session.name}.index-metadata-{uuid.uuid4().hex}.backup"
    )
    try:
        shutil.copytree(rewrite.session, stage)
        write_timeline(stage / "index", rewrite.index)
        write_timeline(stage / "settings", rewrite.settings)
        fsync_directory(stage)
        os.replace(rewrite.session, backup)
        try:
            os.replace(stage, rewrite.session)
        except BaseException:
            os.replace(backup, rewrite.session)
            raise
        shutil.rmtree(backup)
        fsync_directory(rewrite.session.parent)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
        if backup.exists():
            shutil.rmtree(backup)


def print_result(result: ScanResult) -> None:
    print(f"sessions: {result.sessions}")
    print(f"already migrated: {result.already_migrated}")
    print(f"pending migration: {len(result.pending)}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Move index-owned turn/window metadata into settings snapshots.",
    )
    parser.add_argument(
        "--sessions",
        type=Path,
        default=Path.home() / ".kodex" / "sessions",
        help="Kodex sessions directory",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate and report without writing",
    )
    arguments = parser.parse_args()
    sessions = arguments.sessions.expanduser().resolve()
    try:
        if not arguments.check:
            pids = live_kodex_pids()
            if pids:
                print(
                    "Refusing to migrate while kodex-cli is running: "
                    + ", ".join(map(str, pids)),
                    file=sys.stderr,
                )
                return 2
        result = scan(sessions)
        print_result(result)
        if arguments.check or not result.pending:
            return 0
        for rewrite in result.pending:
            apply_rewrite(rewrite)
        verified = scan(sessions)
        if verified.pending:
            raise RuntimeError("migration verification found pending sessions")
        print("migration completed")
        print_result(verified)
        return 0
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        print(f"Migration failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

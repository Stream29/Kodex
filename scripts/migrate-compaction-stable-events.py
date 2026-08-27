#!/usr/bin/env -S uv run python

import argparse
import json
import os
import stat
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

RETAINED_TYPES = {
    "user_message",
    "plan_update",
    "request_user_input_tool_event",
}


@dataclass(frozen=True)
class Rewrite:
    checkpoint_path: Path
    stable_path: Path
    old_checkpoint: bytes
    old_stable: bytes
    new_checkpoint: bytes
    new_stable: bytes


@dataclass(frozen=True)
class ScanResult:
    checkpoints: int
    unchanged: int
    migrated: int
    rewrites: tuple[Rewrite, ...]


def parse_json(path: Path, content: bytes) -> dict:
    try:
        value = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{path}: invalid JSON") from error
    if not isinstance(value, dict):
        raise TypeError(f"{path}: expected a JSON object")
    return value


def encode_json(value: dict) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()


def validate_prefix(path: Path, checkpoint: dict) -> None:
    prefix = checkpoint.get("prefix")
    if not isinstance(prefix, list):
        raise TypeError(f"{path}: prefix must be a list")
    for item in prefix:
        if not isinstance(item, dict) or item.get("type") not in RETAINED_TYPES:
            raise ValueError(f"{path}: prefix contains a non-retained clean event")


def validate_compaction_payload(path: Path, payload: dict) -> None:
    if set(payload) - {"id", "encrypted_content"}:
        raise ValueError(f"{path}: unexpected compaction payload fields")
    if not isinstance(payload.get("encrypted_content"), str):
        raise TypeError(f"{path}: encrypted_content must be a string")
    if payload.get("id") is not None and not isinstance(payload["id"], str):
        raise TypeError(f"{path}: compaction id must be a string or null")


def scan(sessions: Path) -> ScanResult:
    if not sessions.is_dir():
        raise ValueError(f"{sessions}: sessions directory does not exist")

    checkpoint_count = 0
    unchanged_count = 0
    migrated_count = 0
    rewrites: list[Rewrite] = []

    for checkpoint_path in sorted(sessions.glob("**/compaction/*.json")):
        if not checkpoint_path.stem.isdigit():
            continue

        checkpoint_count += 1
        index = int(checkpoint_path.stem)
        old_checkpoint = checkpoint_path.read_bytes()
        checkpoint = parse_json(checkpoint_path, old_checkpoint)
        validate_prefix(checkpoint_path, checkpoint)

        payload = checkpoint.get("compaction")
        stable_path = checkpoint_path.parent.parent / "stable" / checkpoint_path.name
        if payload is None:
            if stable_path.is_file():
                stable = parse_json(stable_path, stable_path.read_bytes())
                if (
                    stable.get("type") == "context_compaction"
                    and "encrypted_content" in stable
                ):
                    validate_compaction_payload(
                        stable_path,
                        {key: value for key, value in stable.items() if key != "type"},
                    )
                    if checkpoint.get("historyBaseIndex") != index:
                        raise ValueError(
                            f"{checkpoint_path}: migrated historyBaseIndex must equal {index}"
                        )
                    migrated_count += 1
                    continue
            unchanged_count += 1
            continue

        if not isinstance(payload, dict):
            raise TypeError(f"{checkpoint_path}: compaction must be an object")
        validate_compaction_payload(checkpoint_path, payload)
        if checkpoint.get("historyBaseIndex") != index + 1:
            raise ValueError(
                f"{checkpoint_path}: historyBaseIndex must equal {index + 1}"
            )
        if not stable_path.is_file():
            raise ValueError(f"{stable_path}: missing context compaction event")

        old_stable = stable_path.read_bytes()
        stable = parse_json(stable_path, old_stable)
        if stable != {"type": "context_compaction"}:
            raise ValueError(f"{stable_path}: expected the legacy compaction marker")

        new_checkpoint_value = {
            key: value for key, value in checkpoint.items() if key != "compaction"
        }
        new_checkpoint_value["historyBaseIndex"] = index
        new_stable_value = {"type": "context_compaction"}
        if payload.get("id") is not None:
            new_stable_value["id"] = payload["id"]
        new_stable_value["encrypted_content"] = payload["encrypted_content"]
        rewrites.append(
            Rewrite(
                checkpoint_path=checkpoint_path,
                stable_path=stable_path,
                old_checkpoint=old_checkpoint,
                old_stable=old_stable,
                new_checkpoint=encode_json(new_checkpoint_value),
                new_stable=encode_json(new_stable_value),
            )
        )

    return ScanResult(
        checkpoints=checkpoint_count,
        unchanged=unchanged_count,
        migrated=migrated_count,
        rewrites=tuple(rewrites),
    )


def live_kodex_pids() -> list[int]:
    proc = Path("/proc")
    if not proc.is_dir():
        return []
    result = []
    for process in proc.iterdir():
        if not process.name.isdigit():
            continue
        try:
            if (process / "comm").read_text().strip() == "kodex-cli":
                result.append(int(process.name))
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    return sorted(result)


def stage(path: Path, content: bytes) -> Path:
    temporary = path.with_name(f".{path.name}.migration-{uuid.uuid4().hex}.tmp")
    mode = stat.S_IMODE(path.stat().st_mode)
    try:
        with temporary.open("xb") as output:
            os.chmod(temporary, mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return temporary


def apply_rewrites(rewrites: tuple[Rewrite, ...]) -> None:
    staged: list[tuple[Path, Path]] = []
    try:
        for rewrite in rewrites:
            staged.append(
                (stage(rewrite.stable_path, rewrite.new_stable), rewrite.stable_path)
            )
            staged.append(
                (
                    stage(rewrite.checkpoint_path, rewrite.new_checkpoint),
                    rewrite.checkpoint_path,
                )
            )

        for rewrite in rewrites:
            if rewrite.stable_path.read_bytes() != rewrite.old_stable:
                raise RuntimeError(f"{rewrite.stable_path}: changed after preflight")
            if rewrite.checkpoint_path.read_bytes() != rewrite.old_checkpoint:
                raise RuntimeError(
                    f"{rewrite.checkpoint_path}: changed after preflight"
                )

        for temporary, target in staged:
            os.replace(temporary, target)

        for directory in {target.parent for _, target in staged}:
            descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)


def print_result(result: ScanResult) -> None:
    print(f"checkpoints: {result.checkpoints}")
    print(f"unchanged: {result.unchanged}")
    print(f"already migrated: {result.migrated}")
    print(f"pending migration: {len(result.rewrites)}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Move legacy checkpoint compaction payloads into stable events.",
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

    try:
        result = scan(arguments.sessions.expanduser().resolve())
        print_result(result)
        if arguments.check or not result.rewrites:
            return 0

        pids = live_kodex_pids()
        if pids:
            print(
                "Refusing to migrate while kodex-cli is running: "
                + ", ".join(map(str, pids)),
                file=sys.stderr,
            )
            return 2

        apply_rewrites(result.rewrites)
        verified = scan(arguments.sessions.expanduser().resolve())
        if verified.rewrites:
            raise RuntimeError("migration verification found pending checkpoints")
        print("migration completed")
        print_result(verified)
        return 0
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        print(f"Migration failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

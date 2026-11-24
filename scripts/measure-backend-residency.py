"""Compare the released and candidate Linux daemons in disposable generation-1 Homes.

Run from an approved loopback-only network namespace. Supply the filtered Native
host test executable (linkDebugTestLinuxX64), the verified v0.8.0 ELF, and the
candidate ELF. Start with --smoke; --compare and --default require full windows.
This driver never downloads, builds, or reads the caller's configuration.
"""

import argparse
from contextlib import closing, contextmanager
from datetime import datetime
import fcntl
from hashlib import file_digest
import json
import os
from pathlib import Path
import re
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from xml.etree import ElementTree


RELEASE_SHA = "7477f28c0bc5807f09d8ea31abec302969c4be3c0d6c7ab8518611b47f626fd0"
MCP_READY_METHOD = "tools/list"
ARENA_OBSERVER_SOURCE = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <malloc.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static int observer_fd;

static void *observe(void *unused) {
    (void)unused;
    char request;
    while (read(observer_fd, &request, 1) == 1) {
        int output = dup(observer_fd);
        if (output < 0) return NULL;
        FILE *reply = fdopen(output, "w");
        if (reply == NULL) { close(output); return NULL; }
        int result = malloc_info(0, reply);
        if (result == 0) fputc('\0', reply);
        if (fclose(reply) || result != 0) break;
    }
    close(observer_fd);
    return NULL;
}

__attribute__((constructor)) static void start_observer(void) {
    const char *value = getenv("KODEX_FIXTURE_ARENA_FD");
    if (value == NULL) return;
    observer_fd = atoi(value);
    unsetenv("KODEX_FIXTURE_ARENA_FD");
    unsetenv("LD_PRELOAD");  /* Only the measured daemon receives the observer. */
    if (fcntl(observer_fd, F_SETFD, FD_CLOEXEC) < 0) _exit(111);
    pthread_t worker;
    if (pthread_create(&worker, NULL, observe, NULL) != 0) _exit(111);
    pthread_detach(worker);
}
"""


def emit(kind, **values):
    print(json.dumps({"kind": kind, **values}), flush=True)


def sha(path):
    with path.open("rb") as handle:
        return file_digest(handle, "sha256").hexdigest()


def proc_start(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()[19]
    except (FileNotFoundError, ProcessLookupError):
        return None


def fixture_failure_excerpt(path):
    """Keep both ends and the beginning of a recent TeamCity failure record."""
    marker = b"##teamcity[testFailed "
    with path.open("rb") as log:
        size = log.seek(0, os.SEEK_END)
        log.seek(0)
        if size <= 5000:
            return log.read(5000).decode(errors="replace")
        head = log.read(1000)
        log.seek(max(0, size - 65_536))
        recent = log.read(65_536)

    parts = [head]
    offset = recent.rfind(b"\n" + marker)
    if offset >= 0 or recent.startswith(marker):
        start = offset + 1
        end = recent.find(b"\n", start)
        parts += [
            b"[Fixture failure record prefix]",
            recent[start:min(start + 2200, end if end >= 0 else len(recent))],
        ]
    parts += [b"[Fixture log tail]", recent[-1800:]]
    return b"\n".join(parts).decode(errors="replace")


def mcp_mock(marker):
    """Answer a genuine stdio handshake and then keep the pipe open until EOF."""
    marker = Path(marker)
    if marker.parent != Path.cwd() or not marker.name.startswith("mcp-"):
        raise ValueError("MCP fixture marker is outside its work directory")
    for line in sys.stdin:
        message = json.loads(line)
        if "id" not in message:
            continue
        response = {"jsonrpc": "2.0", "id": message["id"]}
        method = message.get("method")
        if method == "initialize":
            response["result"] = {
                "protocolVersion": message["params"]["protocolVersion"],
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "residency-fixture", "version": "1"},
            }
        elif method == MCP_READY_METHOD:
            response["result"] = {"tools": []}
        elif method == "ping":
            response["result"] = {}
        else:
            response["error"] = {"code": -32601, "message": "Unsupported fixture method"}
        print(json.dumps(response), flush=True)
        if method == MCP_READY_METHOD and not marker.exists():
            marker.write_text(f"{os.getpid()} {proc_start(os.getpid())}\n")


def wait_for(label, predicate, *, timeout=30, proc=None, between=None):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if proc is not None and proc.poll() is not None:
            raise RuntimeError(f"Owned child exited while waiting for {label}: {proc.returncode}")
        if result := predicate():
            return result
        if between:
            between()
        time.sleep(0.15)
    raise TimeoutError(label)


def snapshot(pid):
    status = Path(f"/proc/{pid}/status").read_text().splitlines()
    rollup = Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines()

    def value(lines, field):
        return int(next(line for line in lines if line.startswith(field + ":")).split()[1])

    return {"threads": value(status, "Threads"), "pss_kib": value(rollup, "Pss")}


def task_switches(pid):
    counters = {}
    for task in Path(f"/proc/{pid}/task").iterdir():
        try:
            values = (task / "status").read_text().splitlines()
        except FileNotFoundError:
            continue
        counters[task.name] = sum(int(line.split()[1]) for line in values if
                                  line.startswith(("voluntary_ctxt_switches:", "nonvoluntary_ctxt_switches:")))
    return counters


def stop_owned(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=3)


def build_arena_observer(root, compiler):
    source, library = root / "arena.c", root / "arena.so"
    source.write_text(ARENA_OBSERVER_SOURCE)
    subprocess.run(
        [str(compiler), "-shared", "-fPIC", "-pthread", "-O2", "-o", library, source],
        check=True, cwd=root, env={"PATH": "/usr/bin:/bin", "HOME": str(root), "TMPDIR": str(root)},
        timeout=20,
    )
    return library


def arena_count(observer):
    observer.sendall(b"m")
    pieces = bytearray()
    while not pieces.endswith(b"\0"):
        block = observer.recv(65536)
        if not block:
            raise RuntimeError("Arena observer exited without a complete malloc_info response.")
        pieces.extend(block)
        if len(pieces) > 4 * 1024 * 1024:
            raise RuntimeError("Unreasonably large malloc_info response.")
    report = ElementTree.fromstring(pieces[:-1])
    assert report.tag == "malloc"
    count = len(report.findall("./heap"))
    assert count > 0
    return count


class Case:
    def __init__(self, base, label, executable, fixture, smoke, arena_library=None):
        self.root = base / label
        self.root.mkdir(mode=0o700)
        self.label = label
        self.work = self.root / "work"
        self.home = self.root / "os-home" / ".kodex"
        self.executable = executable
        self.fixture = fixture
        self.smoke = smoke
        self.arena_library = arena_library
        self.observer = None
        self.settings = self.home / "settings.yml"
        for folder in (self.home.parent, self.work, self.root / "config", self.root / "tmp"):
            folder.mkdir(mode=0o700)
        (self.root / "fixture").write_text("kodex-residency-fixture-1\n")
        self.env = {
            "PATH": "/usr/bin:/bin", "SHELL": "/bin/sh", "LANG": "C.UTF-8",
            "HOME": str(self.home.parent), "USERPROFILE": str(self.home.parent),
            "ZDOTDIR": str(self.home.parent), "XDG_CONFIG_HOME": str(self.root / "config"),
            "XDG_DATA_HOME": str(self.root / "config"),
            "KODEX_HOME": str(self.home), "CODEX_HOME": str(self.root / "codex"),
            "TMPDIR": str(self.root / "tmp"),
        }

    def run_cli(self, *args, checked=True):
        result = subprocess.run(
            [self.executable, *args], cwd=self.work, env=self.env,
            capture_output=True, text=True, timeout=12,
        )
        if checked and result.returncode:
            raise RuntimeError(f"{args[:2]}: {result.returncode}\n{result.stderr}\n{result.stdout}")
        return json.loads(result.stdout) if result.stdout.strip() else {}

    @contextmanager
    def run_fixture(self, mode, *, report_log=True):
        env = {
            **self.env,
            "KODEX_RESIDENCY_FIXTURE_ROOT": str(self.root),
            "KODEX_RESIDENCY_MODE": mode,
            "KODEX_RESIDENCY_SMOKE": str(int(self.smoke)),
            "TESTBALLOON_INCLUDE_PATTERNS": "*backendResidencyFixtureTest*",
        }
        log = self.root / ("fixture-" + mode + ".log")
        with log.open("wb") as output:
            proc = subprocess.Popen([self.fixture], cwd=self.work, env=env, stdout=output, stderr=output)
            try:
                yield proc
                rc = proc.wait(timeout=300)
                if rc != 0 or not (self.root / f"{mode}-complete").exists():
                    raise RuntimeError(f"Fixture {mode} did not complete (rc={rc})")
            except BaseException:
                if report_log:
                    try:
                        print(fixture_failure_excerpt(log), file=sys.stderr)
                    except OSError as diagnostic_error:
                        print(f"Fixture log excerpt unavailable: {type(diagnostic_error).__name__}", file=sys.stderr)
                raise
            finally:
                stop_owned(proc)

    @contextmanager
    def daemon(self, *, stop_signal=None, max_stop_ms=None, report_log=True):
        log = self.root / "daemon.log"
        env = self.env
        pass_fds = ()
        peer = None
        if self.arena_library is not None:
            self.observer, peer = socket.socketpair()
            self.observer.settimeout(4)
            pass_fds = (peer.fileno(),)
            env = {**env, "LD_PRELOAD": str(self.arena_library), "KODEX_FIXTURE_ARENA_FD": str(peer.fileno())}
        with log.open("wb") as output:
            try:
                proc = subprocess.Popen(
                    [self.executable, "backend", "start"], cwd=self.work, env=env, pass_fds=pass_fds,
                    stdin=subprocess.DEVNULL, stdout=output, stderr=output, start_new_session=True,
                )
            except BaseException:
                if self.observer is not None:
                    self.observer.close()
                raise
            finally:
                if peer is not None:
                    peer.close()
            try:
                def ready():
                    status = self.run_cli("backend", "status", checked=False)
                    return status if status.get("phase") == "ready" and status.get("pid") == proc.pid else None

                status = wait_for("own daemon ready", ready, proc=proc)
                assert status["idle_ttl_seconds"] == -1, status["idle_ttl_seconds"]
                emit("daemon", case=self.label, version=status["application_version"], pid=proc.pid)
                yield proc
                started = time.monotonic()
                if stop_signal is None:
                    self.run_cli("backend", "stop")
                else:
                    proc.send_signal(stop_signal)
                assert proc.wait(timeout=15) == 0
                elapsed_ms = (time.monotonic() - started) * 1000
                lock = self.home / ".locks/backend/instance.lock"
                with lock.open("rb") as handle:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                emit("exit", case=self.label, stop_and_join_ms=round(elapsed_ms, 2))
                if max_stop_ms is not None:
                    assert elapsed_ms < max_stop_ms, (elapsed_ms, max_stop_ms)
            except BaseException:
                if report_log:
                    print(log.read_text(errors="replace")[-5000:], file=sys.stderr)
                if proc.poll() is None:
                    try:
                        self.run_cli("backend", "stop")
                        proc.wait(timeout=4)
                    except (RuntimeError, subprocess.TimeoutExpired, ValueError):
                        pass
                raise
            finally:
                if self.observer is not None:
                    self.observer.close()
                stop_owned(proc)

    def configure(self, pipes):
        # JSON strings are valid YAML scalars. Never put a user command/configuration in the fixture.
        settings = ["backend:", "  daemon_ttl_seconds: -1", "  session_title:", "    enabled: false"]
        if pipes:
            settings.append("  mcp_servers:")
        for index in range(pipes):
            marker = self.work / f"mcp-{index}"
            args = [str(Path(__file__).resolve()), "--mcp-mock", str(marker)]
            settings += [
                f"    fixture-{index}:",
                "      type: stdio",
                f"      command: {json.dumps(sys.executable)}",
                f"      args: {json.dumps(args)}",
                f"      working_directory: {json.dumps(str(self.work))}",
            ]
        # Reload reads the complete file.
        temporary = self.settings.with_suffix(".fixture")
        temporary.write_text("\n".join(settings) + "\nfrontend: {}\n")
        temporary.replace(self.settings)

    def compare(self):
        with self.run_fixture("seed"):
            pass
        with closing(sqlite3.connect(f"{(self.home / 'kodex.db').as_uri()}?mode=ro", uri=True)) as db:
            sessions = db.execute("SELECT count(*) FROM sessions").fetchone()[0]
            commits = db.execute("SELECT count(*) FROM agent_commits").fetchone()[0]
        emit("fixture", case=self.label, sessions=sessions, commits=commits, db_bytes=(self.home / "kodex.db").stat().st_size)
        self.configure(0)
        with self.daemon() as daemon:
            pid = daemon.pid
            self.measure(pid, "cold-ready")
            with self.run_fixture("warm"):
                pass
            self.measure(pid, "warm")
            pipes = 1 if self.smoke else 12
            self.configure(pipes)
            self.run_cli("backend", "reload")
            markers = [self.work / f"mcp-{index}" for index in range(pipes)]

            def pipe_processes():
                try:
                    processes = [item.read_text().split() for item in markers]
                    return processes if all(proc_start(int(p)) == start for p, start in processes) else None
                except (FileNotFoundError, ValueError):
                    return None

            children = wait_for("live MCP Pipe handshakes", pipe_processes, proc=daemon)
            with self.run_fixture("load") as driver:
                ready = self.root / "terminals-ready"
                wait_for("PTY registration and marker", ready.exists, proc=driver)
                assert int(ready.read_text()) == pipes
                assert pipe_processes() == children
                emit("terminals", case=self.label, pipe=pipes, pty=pipes)
                self.measure(pid, "terminals-active")
                for index, expected in enumerate((1, 1, 1, 2) if self.smoke else (5, 5, 5, 12)):
                    active = self.root / f"round-{index}-active"
                    peak = {}

                    def account():
                        if self.observer is not None:
                            return
                        for key, value in snapshot(pid).items():
                            peak[key] = max(value, peak.get(key, 0))

                    wait_for("concurrent history/fork", active.exists, proc=driver, between=account, timeout=70)
                    assert int(active.read_text()) == expected
                    assert pipe_processes() == children
                    if self.observer is not None:
                        self.measure(pid, f"round-{index + 1}-active")
                    else:
                        account()
                        emit("peak", case=self.label, round=index + 1, concurrent_sessions=expected, **peak)
                    (self.root / f"round-{index}-release").touch()
                    done = self.root / f"round-{index}-disconnected"
                    # The last success can exit the driver just as it writes this marker.
                    wait_for("headless RPC disconnect", done.exists, timeout=15)
            self.configure(0)
            self.run_cli("backend", "reload")
            wait_for("all fixture Pipe processes closed", lambda: not any(
                proc_start(int(child)) == start for child, start in children
            ), proc=daemon)
            closed = time.monotonic()
            for elapsed in (() if self.smoke else (20, 80)):
                time.sleep(max(0, closed + elapsed - time.monotonic()))
                self.measure(pid, f"closed+{elapsed}s")

    def measure(self, pid, point):
        if self.observer is None:
            emit("sample", case=self.label, point=point, **snapshot(pid))
        else:
            count = arena_count(self.observer)
            emit("arena", case=self.label, point=point, malloc_info_heaps=count)
            if self.label == "candidate" and point == "closed+80s":
                assert count <= 4, "Candidate exceeded the recovered Arena budget."

    def verify_signals(self, chosen):
        with self.run_fixture("seed-empty"):
            pass
        self.configure(0)  # Explicit -1; valid before the product default changes.
        with self.daemon(stop_signal=chosen, max_stop_ms=500) as daemon:
            if chosen == signal.SIGTERM:
                time.sleep(2)  # Keep a separate, untraced quiet sample after startup.
                before = task_switches(daemon.pid)
                started = time.monotonic()
                time.sleep(5)
                after = task_switches(daemon.pid)
                common = before.keys() & after.keys()
                emit("idle", case=self.label, seconds=round(time.monotonic() - started, 3),
                     stable_threads=len(common), appeared=len(after.keys() - before.keys()),
                     vanished=len(before.keys() - after.keys()),
                     context_switches=sum(after[tid] - before[tid] for tid in common))

    def verify_pty_close(self):
        """Run only the real 12-PTY close diagnostic, without comparison or history."""
        phase = "seed"
        try:
            with self.run_fixture("seed-pty-close", report_log=False):
                pass
            with closing(sqlite3.connect(f"{(self.home / 'kodex.db').as_uri()}?mode=ro", uri=True)) as db:
                sessions = db.execute("SELECT count(*) FROM sessions").fetchone()[0]
            if sessions != 12:
                raise RuntimeError("Expected exactly 12 fixture sessions")
            emit("pty-close", phase=phase, sessions=sessions, result="ok")
            self.configure(0)
            phase = "backend-ready"
            driver_failure = None
            with self.daemon(report_log=False):
                emit("pty-close", phase=phase, result="ok")
                phase = "rpc-driver"
                try:
                    with self.run_fixture("load-pty-close", report_log=False):
                        pass
                except Exception as failure:
                    driver_failure = type(failure).__name__
                    emit("pty-close", phase=phase, result="error", reason=driver_failure)
                finally:
                    self.report_pty_close_evidence()
                phase = "backend-stop"
            emit("pty-close", phase=phase, result="rc0-lock-released")
        except Exception as failure:
            emit("pty-close", phase=phase, result="error", reason=type(failure).__name__)
            raise RuntimeError(f"PTY close diagnostic stopped at {phase}") from None
        if driver_failure is not None:
            raise RuntimeError("PTY close diagnostic failed in the RPC driver after clean backend stop")

    def report_pty_close_evidence(self):
        slot_phases = ("resume-ack", "ready-marker", "registration", "stdin-ack", "registry-lookup",
                       "registry-observation", "exit-observation", "exit-code", "tail-check", "tail-marker")
        phases = [(str(slot), phase) for slot in range(12) for phase in slot_phases]
        phases += [("all", phase) for phase in
                   ("client-scope", "keepalive-loop", "ready-barrier", "keepalive-close", "client-close")]
        allowed = rb"(?:pty:(?P<pty_id>[0-9]{1,10});)?(?P<result>started|ok|cancelled|ready|missing|changed|found|lost|seen|code:-?[0-9]{1,10}|error:[A-Za-z][A-Za-z0-9_]{0,79})\n"
        for slot, phase in phases:
            path = self.root / f"pty-evidence-{slot}-{phase}"
            try:
                with path.open("rb") as record:
                    data = record.read(160)
            except FileNotFoundError:
                continue
            except OSError:
                emit("pty-close", phase=phase, slot=slot, result="evidence-unreadable")
                continue
            if match := re.fullmatch(allowed, data):
                pty_id = match["pty_id"]
                emit("pty-close", phase=phase, slot=slot,
                     pty_id=int(pty_id) if pty_id is not None else None,
                     result=match["result"].decode("ascii"))
            else:
                emit("pty-close", phase=phase, slot=slot, result="evidence-invalid")

    def verify_default(self):
        with self.run_fixture("seed-default"):
            pass
        assert not self.settings.exists()
        schedule, await_agent, deadline = (self.root / "default-ids").read_text().splitlines()
        deadline_epoch = parse_wall(deadline)
        with self.daemon() as daemon:
            self.run_cli("schedule", "create", "--agent", schedule,
                         "--at", deadline, "--description", "Residency schedule fixture")
            disconnected_wall = time.time()
            disconnected_mono = time.monotonic()
            assert deadline_epoch - disconnected_wall > 305, "Not enough client-free wall time."
            assert not self.settings.exists()
            # No clients/RPC reads until the full interval has elapsed.
            time.sleep(max(0, deadline_epoch - disconnected_wall))
            assert time.monotonic() - disconnected_mono > 300

            def fired():
                with closing(sqlite3.connect(f"{(self.home / 'kodex.db').as_uri()}?mode=ro", uri=True)) as db:
                    return [
                        db.execute("SELECT created_at, payload_json FROM agent_commits WHERE agent_id=? AND type=?",
                                   (agent, kind)).fetchall()
                        for agent, kind in ((int(schedule), "event_schedule"), (int(await_agent), "await_event_tool_event"))
                    ]

            def both_fired():
                rows = fired()
                return rows if all(rows) else None

            scheduled, awaited = wait_for("both durable deadline events", both_fired, proc=daemon, timeout=5)
            assert len(scheduled) == len(awaited) == 1
            assert "Residency schedule fixture" in scheduled[0][1]
            assert json.loads(awaited[0][1])["call_id"] == "residency-await"
            lags = [round(parse_wall(row[0][0]) - deadline_epoch, 3) for row in (scheduled, awaited)]
            assert all(0 <= lag < 5 for lag in lags), lags
            assert self.run_cli("backend", "status")["pid"] == daemon.pid
            emit("deadlines", browser_or_tui=False, no_client_seconds=round(time.monotonic() - disconnected_mono, 3),
                 schedule_delay_seconds=lags[0], await_delay_seconds=lags[1])


def parse_wall(instant):
    return datetime.fromisoformat(instant.replace("Z", "+00:00")).timestamp()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--arena-compiler", type=Path,
                        help="Approved C compiler for separate --arena-only trials; not used in main comparison.")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--smoke", action="store_true")
    modes.add_argument("--compare", action="store_true")
    modes.add_argument("--arena-only", action="store_true")
    modes.add_argument("--default", action="store_true")
    modes.add_argument("--signals", action="store_true")
    modes.add_argument("--pty-close-diagnostic", action="store_true",
                       help="Test one candidate's 12 PTY exits, without history or memory acceptance.")
    modes.add_argument("--mcp-mock")
    args = parser.parse_args()
    if args.mcp_mock:
        mcp_mock(args.mcp_mock)
        return
    if not sys.platform.startswith("linux") or {name for _, name in socket.if_nameindex()} != {"lo"}:
        parser.error("Run this fixture in an approved, private loopback-only Linux network namespace.")
    if args.fixture is None or args.candidate is None or ((args.smoke or args.compare or args.arena_only) and args.release is None):
        parser.error("Supply --fixture and --candidate; comparison additionally requires --release.")
    if args.arena_only != (args.arena_compiler is not None):
        parser.error("--arena-only and --arena-compiler must be specified together.")
    if args.pty_close_diagnostic and args.release is not None:
        parser.error("--pty-close-diagnostic takes only one candidate.")
    fixture = args.fixture.resolve(strict=True)
    candidate = args.candidate.resolve(strict=True)
    release = args.release.resolve(strict=True) if args.release is not None else None
    if release is not None and sha(release) != RELEASE_SHA:
        parser.error("Release executable is not the verified Linux x64 v0.8.0 ELF.")
    method = ("Separate malloc_info trial, one preload worker; PSS and Threads are excluded"
              if args.arena_only else "Daemon /proc/Pss and Threads; page cache not dropped; no Arena observer")
    if args.pty_close_diagnostic:
        method = "Single-candidate 12-PTY close diagnostic; no memory acceptance"
    emit("environment", uname=tuple(os.uname()), glibc=os.confstr("CS_GNU_LIBC_VERSION"), load=os.getloadavg(),
         candidate_sha256=sha(candidate), release_sha256=RELEASE_SHA if release else None,
         fixture_sha256=sha(fixture), method=method)
    with tempfile.TemporaryDirectory(prefix="kodex-residency-", dir="/tmp") as directory:
        root = Path(directory)
        if args.default:
            Case(root, "default", candidate, fixture, False).verify_default()
        elif args.pty_close_diagnostic:
            Case(root, "pty-close", candidate, fixture, False).verify_pty_close()
        elif args.signals:
            for label, sig in (("signal-term", signal.SIGTERM), ("signal-int", signal.SIGINT), ("signal-stop", None)):
                Case(root, label, candidate, fixture, True).verify_signals(sig)
        else:
            library = build_arena_observer(root, args.arena_compiler.resolve(strict=True)) if args.arena_only else None
            Case(root, "release", release, fixture, args.smoke, library).compare()
            Case(root, "candidate", candidate, fixture, args.smoke, library).compare()


if __name__ == "__main__":
    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    main()

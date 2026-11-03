import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { test } from "node:test";
import vm from "node:vm";
import * as tools from "./local-entry-tools.mjs";

const source = await readFile(new URL("./check-local-entry.mjs", import.meta.url), "utf8");
const baseline = process.env.LOCAL_ENTRY_BASELINE
    ? await readFile(process.env.LOCAL_ENTRY_BASELINE, "utf8") : null;
if (baseline) assert.equal(createHash("sha256").update(baseline).digest("hex"),
    "ff1d1f009406bbab30f6e5fcb77a1acb64e1de57c31629c579c5849c61ac4c22");
const paths = { temporary: "/private/check", home: "/private/check/home",
    bin: "/private/check/bin", runtime: "/private/check/runtime", capture: "/private/check/launch.private" };

class FakeChild extends EventEmitter {
    constructor(onKill = () => {}) {
        super();
        this.pid = 123;
        this.stdout = new EventEmitter();
        this.stderr = new EventEmitter();
        this.exitCode = this.signalCode = null;
        this.signals = [];
        this.kill = name => { this.signals.push(name); onKill(name, this); return true; };
    }
    exit(code, signal = null) {
        this.exitCode = code; this.signalCode = signal;
        this.emit("exit", code, signal);
    }
}
function owned(child, onError) {
    return tools.spawnOwned("/private/check/bin/kodex", [], {}, { spawnImpl: () => child, onError });
}
function legacyStop(host) {
    const body = baseline.slice(baseline.indexOf("async function stopHost("), baseline.indexOf("\n\ntry {"));
    return vm.runInNewContext(`${body}; stopHost`, {
        assert, delay: () => Promise.resolve(),
    })(host);
}
async function stop(child) {
    const tracked = owned(child);
    if (baseline) return legacyStop({ child, exited: tracked.done.then(result => result.code) });
    return tools.stopOwned(tracked, { label: "Frontend host", expectedCode: 130, termMs: 1, killMs: 5 });
}
async function cleanup(primary, seen) {
    const fail = new Error("chrome cleanup failed");
    if (!baseline) return tools.cleanupSteps([
        () => { seen.push("chrome"); throw fail; },
        () => { seen.push("host"); },
        () => { seen.push("backend"); },
        () => { seen.push("sandbox"); },
        () => { seen.push("handlers"); },
    ], primary);
    const final = baseline.slice(baseline.lastIndexOf("} finally {") + 2);
    const run = vm.runInNewContext(`(async () => { try { throw primary; } ${final} })`, {
        primary, cleaning: false,
        closeBrowser: async () => { seen.push("chrome"); throw fail; },
        frontendHosts: [{}], stopHost: async () => { seen.push("host"); },
        safeToRemove: false, record: async () => { seen.push("backend"); return null; },
        rm: async () => { seen.push("sandbox"); }, temporary: paths.temporary,
        process: { off: () => { seen.push("handlers"); } }, interruptOwnedCheck() {},
    });
    return run();
}

test("browser and wrapper receive only private allowlist env and cwd", () => {
    const env = tools.privateEnvironment(paths);
    let actual;
    const driver = baseline ?? source;
    const statement = driver.match(/chrome = spawn(?:Owned)?\(process\.env\.CHROME_BIN[\s\S]+?\], \{[^\n]+\}\);/)[0];
    const captureSpawn = (_file, _args, options) => { actual = options; return {}; };
    vm.runInNewContext(statement, {
        process: { env: { CHROME_BIN: "/fake/wrapper", HOME: "/REAL", XDG_CONFIG_HOME: "/REAL/config",
            NODE_OPTIONS: "--require=/REAL/hook", SECRET: "must-not-inherit" } },
        profile: "/private/check/chrome", env, home: paths.home,
        spawn: captureSpawn, spawnOwned: captureSpawn, failWork() {},
    });
    assert.deepEqual(actual.env, env);
    assert.equal(actual.cwd, paths.home);
    for (const [key, value] of Object.entries(env)) {
        assert.ok(!value.includes("/REAL"));
        if (/HOME|^XDG_|TMPDIR|ZDOTDIR/.test(key)) assert.ok(value.startsWith(paths.temporary), key);
    }
    assert.equal(env.SECRET, undefined);
    assert.equal(env.NODE_OPTIONS, undefined);
    assert.equal(env.DISPLAY, undefined);
    assert.equal(env.WAYLAND_DISPLAY, undefined);
});

test("Chrome ENOENT is observed immediately by an awaited failure", async () => {
    const child = new FakeChild();
    child.pid = undefined;
    const error = Object.assign(new Error("fake missing Chrome"), { code: "ENOENT" });
    let done;
    if (baseline) {
        const statement = baseline.match(/chromeExit = new Promise[^\n]+/)[0];
        const context = { chrome: child };
        vm.runInNewContext(statement, context);
        done = context.chromeExit;
    } else done = owned(child).done;
    assert.doesNotThrow(() => child.emit("error", error));
    assert.equal((await done).error, error);
});

test("TERM-unresponsive host is KILLed but validation still fails", async () => {
    const child = new FakeChild((name, self) => {
        if (name === "SIGKILL") queueMicrotask(() => self.exit(null, name));
    });
    await assert.rejects(stop(child));
    assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
    assert.equal(child.signalCode, "SIGKILL");
});

test("cleanup continues through other host, backend, sandbox and handlers", async () => {
    const seen = [];
    await assert.rejects(cleanup(new Error("primary"), seen));
    assert.deepEqual(seen, ["chrome", "host", "backend", "sandbox", "handlers"]);
});

test("original verification error wins over cleanup failures", async () => {
    const primary = new Error("original verification failed");
    const caught = await cleanup(primary, []).catch(error => error);
    assert.equal(caught, primary);
    assert.equal(caught.cleanupErrors[0].message, "chrome cleanup failed");
});

test("already signalled host remains a validation failure without another kill", async () => {
    const child = new FakeChild(), tracked = owned(child);
    child.exit(null, "SIGTERM");
    await assert.rejects(tools.stopOwned(tracked, { expectedCode: 130 }));
    assert.deepEqual(child.signals, []);
});

test("host exit 130 is preserved and repeated cleanup is idempotent", async () => {
    const child = new FakeChild((_, self) => queueMicrotask(() => self.exit(130)));
    const tracked = owned(child);
    await tools.stopOwned(tracked, { expectedCode: 130 });
    await tools.stopOwned(tracked, { expectedCode: 130 });
    assert.deepEqual(child.signals, ["SIGTERM"]);
});

test("cleanup-only failure cannot become success", async () => {
    await assert.rejects(tools.cleanupSteps([() => { throw new Error("cleanup"); }]), AggregateError);
});

test("failed first host still releases second host and private backend, with primary intact", async () => {
    const bad = new FakeChild((_, self) => self.exit(null, "SIGTERM"));
    const good = new FakeChild((_, self) => self.exit(130));
    const primary = new Error("business assertion failed"), seen = [];
    const first = owned(bad), second = owned(good);
    const caught = await tools.cleanupSteps([
        () => tools.stopOwned(first, { expectedCode: 130 }),
        () => tools.stopOwned(second, { expectedCode: 130 }),
        () => seen.push("backend"),
    ], primary).catch(error => error);
    assert.equal(caught, primary);
    assert.equal(caught.cleanupErrors.length, 1);
    assert.deepEqual(good.signals, ["SIGTERM"]);
    assert.deepEqual(seen, ["backend"]);
});

test("even a child ignoring KILL is bounded and does not block remaining cleanup", async () => {
    const child = new FakeChild(), tracked = owned(child), seen = [];
    await assert.rejects(tools.cleanupSteps([
        () => tools.stopOwned(tracked, { termMs: 1, killMs: 1 }),
        () => seen.push("backend"),
    ]));
    assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
    assert.deepEqual(seen, ["backend"]);
});

test("kill error does not pretend the live child has exited", async () => {
    const error = new Error("fake TERM failure");
    const child = new FakeChild((name, self) => {
        if (name === "SIGTERM") self.emit("error", error);
        else self.exit(null, name);
    });
    const tracked = owned(child);
    await assert.rejects(tools.stopOwned(tracked, { termMs: 1, killMs: 1 }));
    assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
});

test("command ENOENT rejects without unhandled error or live watchdog", async () => {
    const child = new FakeChild(); child.pid = undefined;
    const error = Object.assign(new Error("fake missing CLI"), { code: "ENOENT" });
    const result = tools.runOwnedCommand("/fake/missing", [], {}, { spawnImpl: () => child });
    queueMicrotask(() => child.emit("error", error));
    await assert.rejects(result, value => value === error);
    assert.deepEqual(child.signals, []);
});

test("command timeout followed by exit 0 is still failure", async () => {
    const child = new FakeChild((_, self) => self.exit(0));
    await assert.rejects(tools.runOwnedCommand("/fake/cli", [], {}, {
        spawnImpl: () => child, termMs: 1, killAfterMs: 5, killMs: 1,
    }), /deadline/);
    assert.deepEqual(child.signals, ["SIGTERM"]);
});

test("40s watchdog policy escalates and is not erased by expected code", async () => {
    const child = new FakeChild((name, self) => { if (name === "SIGKILL") self.exit(0); });
    await assert.rejects(tools.runOwnedCommand("/fake/cli", [], {}, {
        spawnImpl: () => child, termMs: 1, killAfterMs: 3, killMs: 5,
    }), /deadline/);
    assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
    assert.match(source, /runOwnedCommand/);
});

test("watchdog KILL is bounded, registered, and not followed by a second TERM/KILL cycle", async () => {
    const child = new FakeChild(), children = [];
    await assert.rejects(tools.runOwnedCommand("/fake/cli", [], {}, {
        spawnImpl: () => child, children, termMs: 1, killAfterMs: 3, killMs: 2,
    }), /deadline/);
    assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
    assert.equal(children.length, 1);
    assert.equal(children[0].result, null);
});

test("cancellation rejects command even when it cooperatively returns expected code", async () => {
    const controller = new AbortController(), reason = new Error("cancelled");
    const child = new FakeChild((_, self) => self.exit(0));
    const result = tools.runOwnedCommand("/fake/cli", [], {}, {
        spawnImpl: () => child, signal: controller.signal,
    });
    controller.abort(reason);
    await assert.rejects(result, value => value === reason);
    assert.deepEqual(child.signals, ["SIGTERM"]);
});

test("backend identity change never signals a reused PID", async () => {
    const sent = [];
    await tools.stopIdentifiedProcess({
        identity: { pid: 123, executable: "/private/kodex", start: "1" },
        identify: async () => ({ pid: 123, executable: "/private/kodex", start: "2" }),
        signal: name => sent.push(name), waitUntilGone: async () => false,
    });
    assert.deepEqual(sent, []);
});

test("backend identity is rechecked before KILL and forced cleanup still fails", async () => {
    const identity = { pid: 123, executable: "/private/kodex", start: "1" }, sent = [];
    let alive = true;
    await assert.rejects(tools.stopIdentifiedProcess({
        identity, identify: async () => alive ? identity : null,
        signal: name => { sent.push(name); if (name === "SIGKILL") alive = false; },
        waitUntilGone: async () => !alive,
    }), /cleanup failed/);
    assert.deepEqual(sent, ["SIGTERM", "SIGKILL"]);
});

test("backend PID reused between TERM and KILL is not KILLed", async () => {
    const identity = { pid: 123, executable: "/private/kodex", start: "1" }, sent = [];
    let current = identity;
    await tools.stopIdentifiedProcess({
        identity, identify: async () => current,
        signal: name => { sent.push(name); current = { ...identity, start: "2" }; },
        waitUntilGone: async () => false,
    });
    assert.deepEqual(sent, ["SIGTERM"]);
});

test("driver import is inert (no CLI arguments, filesystem setup, or spawn)", async () => {
    const driver = await import("./check-local-entry.mjs");
    assert.equal(typeof driver.main, "function");
});

async function fakeDriver({ hostMode = "normal", chromeMode = "ENOENT", backendMode = "normal" } = {}) {
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    const body = source.replace(/^import[\s\S]*?from "\.\/local-entry-tools\.mjs";\n/, "")
        .replace("export async function main()", "async function main()")
        .replace(/\nif \(process\.argv\[1\][\s\S]*$/, "\nreturn main();");
    const fakeProcess = new EventEmitter();
    fakeProcess.argv = ["node", "/fake/check.mjs", "/fake/cli", "/private/output"];
    fakeProcess.env = { CHROME_BIN: "/fake/wrapper", HOME: "/REAL", SECRET: "must-not-inherit" };
    let backendRunning = true, removed = false, commandStuck = false;
    const children = [], optionsSeen = [], commands = [], backendSignals = [];
    const missing = Object.assign(new Error("fake missing wrapper"), { code: "ENOENT" });
    const record = { pid: 321, port: 1234, password: "fake-private-password", instanceId: "fake-instance" };
    const url = `http://127.0.0.1:4321/web/${"a".repeat(64)}/#${"b".repeat(64)}`;
    fakeProcess.kill = (pid, name) => {
        assert.equal(pid, record.pid);
        backendSignals.push(name);
        if (name === "SIGKILL" && backendMode !== "unresponsive") backendRunning = false;
    };
    const dependencies = {
        assert, join, resolve, tmpdir: () => "/private", delay: async () => {},
        AbortController, AbortSignal, console,
        process: fakeProcess, ...tools,
        mkdir: async () => {}, mkdtemp: async () => paths.temporary,
        copyFile: async () => {}, chmod: async () => {}, writeFile: async () => {},
        rm: async path => { if (path === paths.temporary) removed = true; },
        readFile: async path => {
            if (path.endsWith("launch.private")) return url;
            if (path.endsWith("connection.json")) return JSON.stringify(record);
            if (path.endsWith("/maps")) return "";
            if (path.endsWith("/status")) return "State: S";
            if (path.endsWith("/stat")) return `321 (owned kodex) S ${Array(18).fill("0").join(" ")} 987`;
            throw Object.assign(new Error("fake missing file"), { code: "ENOENT" });
        },
        readlink: async () => {
            if (backendMode === "unverifiable") throw Object.assign(new Error("fake permission failure"), { code: "EACCES" });
            return backendRunning ? "/private/check/bin/kodex" : null;
        },
        fetch: async () => ({ ok: true, json: async () => ({ phase: "ready" }) }),
        runOwnedCommand: async (_file, args, _options, policy) => {
            commands.push(args.join(" "));
            if (args[0] === "--help") return "kodex web kodex app";
            if (args[0] === "app") {
                assert.equal(policy.expectedCode, 1);
                return "Cannot initialize GTK display";
            }
            if (backendMode === "command-stuck") {
                commandStuck = true;
                policy.children.push(owned(new FakeChild()));
                backendRunning = false;
                return "";
            }
            if (backendMode !== "normal") throw new Error("fake cooperative backend stop failed");
            backendRunning = false;
            return "";
        },
        stopOwned: (child, policy) => tools.stopOwned(child, { ...policy, termMs: 1, killMs: 1 }),
        stopIdentifiedProcess: options => tools.stopIdentifiedProcess({
            ...options, waitUntilGone: async () => !backendRunning,
        }),
        spawnOwned: (file, args, options, hooks) => {
            optionsSeen.push(options);
            const child = new FakeChild((name, self) => {
                if (file === "/fake/wrapper") self.exit(0);
                else if (hostMode === "normal") self.exit(130);
                else if (name === "SIGKILL") self.exit(null, name);
            });
            children.push(child);
            if (file === "/fake/wrapper") {
                if (chromeMode === "ENOENT") {
                    child.pid = undefined;
                    queueMicrotask(() => child.emit("error", missing));
                } else queueMicrotask(() => fakeProcess.emit("SIGTERM"));
                if (hostMode === "signalled") children[0].exit(null, "SIGTERM");
            }
            return tools.spawnOwned(file, args, options, { ...hooks, spawnImpl: () => child });
        },
    };
    const run = new AsyncFunction(...Object.keys(dependencies), body);
    const error = await run(...Object.values(dependencies)).catch(error => error);
    if (chromeMode === "ENOENT") assert.equal(error, missing);
    else assert.match(error.message, /interrupted/);
    assert.deepEqual(children[0].signals, hostMode === "normal" ? ["SIGTERM"]
        : hostMode === "signalled" ? [] : ["SIGTERM", "SIGKILL"]);
    assert.equal(backendRunning, ["unresponsive", "unverifiable"].includes(backendMode));
    assert.equal(removed, !["unresponsive", "unverifiable", "command-stuck"].includes(backendMode));
    assert.equal(fakeProcess.listenerCount("SIGTERM"), 0);
    assert.equal(fakeProcess.listenerCount("SIGINT"), 0);
    assert.equal(commands.includes("backend stop"), backendMode !== "unverifiable");
    assert.equal(optionsSeen[1].cwd, paths.home);
    assert.equal(optionsSeen[1].env.HOME, paths.home);
    assert.equal(optionsSeen[1].env.SECRET, undefined);
    assert.deepEqual(backendSignals, ["normal", "unverifiable", "command-stuck"].includes(backendMode)
        ? [] : ["SIGTERM", "SIGKILL"]);
    if (backendMode === "command-stuck") assert.equal(commandStuck, true);
}
test("driver Chrome ENOENT cleans its fake host/backend/Home and preserves ENOENT", () => fakeDriver());
test("driver host forced cleanup still reaches backend/Home and preserves Chrome ENOENT",
    () => fakeDriver({ hostMode: "unresponsive" }));
test("driver already signalled host still reaches backend/Home and preserves Chrome ENOENT",
    () => fakeDriver({ hostMode: "signalled" }));
test("driver signal flag cancels readiness then cleans owned resources",
    () => fakeDriver({ chromeMode: "cancel" }));
test("driver backend forced cleanup uses exact PID and keeps primary failure",
    () => fakeDriver({ backendMode: "forced" }));
test("driver retains private Home on backend cleanup failure but releases handlers",
    () => fakeDriver({ backendMode: "unresponsive" }));
test("driver retains private Home when proc identity cannot be confirmed",
    () => fakeDriver({ backendMode: "unverifiable" }));
test("driver retains private Home when a cleanup command remains live",
    () => fakeDriver({ backendMode: "command-stuck" }));

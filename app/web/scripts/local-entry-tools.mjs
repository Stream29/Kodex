import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { join } from "node:path";

export function privateEnvironment({ home, bin, runtime, temporary, capture }) {
    return {
        HOME: home, USERPROFILE: home, XDG_CONFIG_HOME: join(home, ".config"),
        XDG_DATA_HOME: join(home, ".local/share"), XDG_CACHE_HOME: join(home, ".cache"),
        XDG_STATE_HOME: join(home, ".local/state"), XDG_RUNTIME_DIR: runtime,
        PATH: `${bin}:/usr/bin:/bin`, TMPDIR: join(temporary, "tmp"),
        KODEX_ENTRY_CAPTURE: capture, KODEX_ENTRY_TOOL_ATTEMPT: join(temporary, "tool-attempt"),
        GDK_BACKEND: "wayland", SHELL: "/bin/sh", ZDOTDIR: home,
        BASH_ENV: "", ENV: "", LANG: "C.UTF-8",
    };
}

// done always fulfills: even an error emitted before the caller awaits is observed.
export function spawnOwned(file, args, options, { spawnImpl = spawn, onError = () => {} } = {}) {
    const child = spawnImpl(file, args, options);
    const owned = { child, result: null, stop: null };
    owned.done = new Promise(resolve => {
        child.on("error", error => {
            owned.error ??= error;
            if (!owned.result && child.pid === undefined) {
                owned.result = { error };
                resolve(owned.result);
            }
            onError(error);
        });
        child.once("exit", (code, signal) => {
            owned.result ??= { code, signal };
            resolve(owned.result);
        });
    });
    return owned;
}

export async function abortable(promise, signal) {
    if (signal?.aborted) {
        Promise.resolve(promise).catch(() => {});
        throw signal.reason;
    }
    let listener;
    try {
        return await Promise.race([promise, new Promise((_, reject) => {
            listener = () => reject(signal.reason);
            signal?.addEventListener("abort", listener, { once: true });
        })]);
    } finally {
        signal?.removeEventListener("abort", listener);
    }
}

async function within(promise, milliseconds, signal) {
    let timer;
    try {
        return await abortable(Promise.race([promise.then(value => ({ value })),
            new Promise(resolve => { timer = setTimeout(() => resolve(null), milliseconds); })]), signal);
    } finally { clearTimeout(timer); }
}

function signalChild(owned, name) {
    if (owned.result) return;
    if (name === "SIGTERM") {
        if (owned.termSentAt !== undefined || owned.killSentAt !== undefined) return;
        owned.termSentAt = Date.now();
    } else {
        if (owned.killSentAt !== undefined) return;
        owned.killSentAt = Date.now();
    }
    owned.child.kill(name);
}

export function stopOwned(owned, { label = "Owned child", expectedCode, termMs = 5000, killMs = 2000 } = {}) {
    owned.stop ??= (async () => {
        let forced = owned.killSentAt !== undefined;
        const errors = [];
        if (!owned.result) {
            if (owned.killSentAt === undefined) {
                try { signalChild(owned, "SIGTERM"); } catch (error) { errors.push(error); }
                if (!await within(owned.done, termMs)) {
                    forced = true;
                    // ChildProcess.kill targets this live child handle, never a name or port.
                    try { signalChild(owned, "SIGKILL"); } catch (error) { errors.push(error); }
                }
            }
            if (owned.killSentAt !== undefined && !await within(owned.done,
                Math.max(0, killMs - (Date.now() - owned.killSentAt))))
                errors.push(new Error(`${label} did not exit after KILL`));
        }
        if (owned.error) errors.push(owned.error);
        if (forced) errors.push(new Error(`${label} required forced cleanup`));
        if (owned.result && !owned.result.error) {
            const { code, signal } = owned.result;
            try {
                if (expectedCode !== undefined) assert.equal(code, expectedCode, `${label} unexpected exit (${signal ?? code})`);
                else assert.ok(code === 0 || signal === "SIGTERM", `${label} unexpected exit (${signal ?? code})`);
            } catch (error) { errors.push(error); }
        }
        if (errors.length) throw new AggregateError(errors, `${label} cleanup failed`, { cause: errors[0] });
    })();
    return owned.stop;
}

export async function cleanupSteps(steps, primary) {
    const errors = [];
    for (const step of steps) {
        try { await step(); } catch (error) { errors.push(error); }
    }
    if (primary) {
        if (errors.length) primary.cleanupErrors = [...(primary.cleanupErrors ?? []), ...errors];
        throw primary;
    }
    if (errors.length) throw new AggregateError(errors, "Owned local-entry cleanup failed", { cause: errors[0] });
}

export async function runOwnedCommand(file, args, options, {
    expectedCode = 0, signal, spawnImpl, onError, children,
    termMs = 30_000, killAfterMs = 40_000, killMs = 2000,
} = {}) {
    signal?.throwIfAborted();
    const owned = spawnOwned(file, args, options, { spawnImpl, onError });
    children?.push(owned);
    let text = "", deadline;
    owned.child.stdout.on("data", bytes => { text += bytes; });
    owned.child.stderr.on("data", bytes => { text += bytes; });
    const timer = setTimeout(() => {
        deadline ??= new Error("Owned command exceeded TERM deadline");
        try { signalChild(owned, "SIGTERM"); } catch (error) { deadline.cause = error; }
    }, termMs);
    const killTimer = setTimeout(() => {
        deadline ??= new Error("Owned command exceeded KILL deadline");
        try { signalChild(owned, "SIGKILL"); } catch (error) { deadline.cause = error; }
    }, killAfterMs);
    let primary;
    try {
        const completed = await within(owned.done, killAfterMs + killMs, signal);
        if (!completed) throw deadline ?? new Error("Owned command did not exit after watchdog");
        const result = completed.value;
        if (result.error) throw result.error;
        if (deadline) throw deadline;
        assert.equal(result.code, expectedCode, "Owned kodex command had an unexpected exit; private output is not printed.");
    } catch (error) { primary = error; }
    finally {
        clearTimeout(timer); clearTimeout(killTimer);
        await cleanupSteps([() => stopOwned(owned, { label: "Owned command", expectedCode, termMs: 1000, killMs })], primary);
    }
    return text;
}

// A daemon has no ChildProcess handle: recheck the exact executable/start-time
// identity immediately before each signal; a reused PID is never signalled.
export async function stopIdentifiedProcess({ identity, identify, signal, waitUntilGone }) {
    const same = async () => JSON.stringify(await identify()) === JSON.stringify(identity);
    if (!await same()) return;
    const errors = [];
    for (const [name, duration] of [["SIGTERM", 5000], ["SIGKILL", 2000]]) {
        if (!await same()) break;
        try { signal(name); } catch (error) { errors.push(error); }
        if (name === "SIGKILL") errors.push(new Error("Owned backend required forced cleanup"));
        if (await waitUntilGone(duration)) break;
    }
    if (await same()) errors.push(new Error("Owned backend cleanup incomplete"));
    if (errors.length) throw new AggregateError(errors, "Owned backend cleanup failed", { cause: errors[0] });
}

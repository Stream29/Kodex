import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { chmod, mkdtemp, mkdir, readFile, readlink, realpath, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { cleanupSteps, privateEnvironment, spawnOwned, stopOwned } from "./local-entry-tools.mjs";

// Takes only build outputs. Every process, profile, backend Home and fixed shell fixture is private.
const [mode, fixturePath, binaryPath] = process.argv.slice(2);
assert.ok(process.platform === "linux" && ["browser", "tui"].includes(mode),
    "Usage: node check-terminal-inspector.mjs browser|tui FIXTURE PRODUCTION_WEBSITE|NATIVE_CLI");
assert.equal(process.argv.length, 5);
const fixture = await realpath(fixturePath);
const binary = await realpath(binaryPath);
const chrome = mode === "browser" ? await realpath(process.env.CHROME_BIN) : null;
const root = await mkdtemp("/tmp/kodex-terminal-inspector-");
await chmod(root, 0o700);
const home = join(root, "os-home"), tmp = join(root, "tmp"), project = join(root, "project"), runtime = join(root, "runtime");
for (const dir of [home, tmp, project, runtime]) await mkdir(dir, { mode: 0o700 });
const env = {
    ...privateEnvironment({ home, bin: root, runtime, temporary: root, capture: join(root, "unused") }),
    TERM: "xterm-256color", KODEX_CHECK_TERMINAL_INSPECTOR: "1",
    ...(mode === "browser" ? { KODEX_BROWSER_FIXTURE_WEBSITE: binary } : {}),
};
await writeFile(join(tmp, "emit-terminal-inspector"), [
    "#!/bin/sh",
    "set -eu",
    'printf "%s\\n" FIXTURE_BOOT',
    'i=0; while [ ! -f "$TMPDIR/start-terminal-output" ]; do',
    '  [ "$i" -lt 300 ] || exit 91; i=$((i+1)); sleep .1',
    "done",
    "i=1; while [ \"$i\" -le 24 ]; do",
    '  stamp=$(/usr/bin/date +%s%3N)',
    '  printf "\\033[32mT%02d@%s\\033[0m\\n" "$i" "$stamp"',
    '  sleep .35; i=$((i+1))',
    "done",
    "sleep 2",
    "printf '\\033]0;NEVER_RENDER_TERMINAL_FIXTURE_TITLE\\007'",
    "awk 'BEGIN { for (i=0; i<9000; i++) printf \"load %05d %.48s\\n\", i, \"012345678901234567890123456789012345678901234567\" }'",
    'printf "%s\\n" FIXTURE_FINAL',
    "exit 23",
].join("\n") + "\n", { mode: 0o600 });

const stopped = new AbortController();
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal,
    () => stopped.abort(new Error("Owned smoke interrupted")));
let stage = "fixture", frontend, backend, cdp, tty, ttySnapshot, serverText = "", failure, beforeInitialYieldMs;
const stamps = new Map();

async function wait(label, read, timeout = 25_000) {
    stage = label;
    const limit = Date.now() + timeout;
    while (Date.now() < limit) {
        stopped.signal.throwIfAborted();
        assert.equal(backend?.result ?? null, null, "Private backend exited");
        assert.equal(frontend?.result ?? null, null, "Private frontend exited");
        const value = await read();
        if (value) return value;
        await delay(25, undefined, { signal: stopped.signal });
    }
    throw new Error(`Timed out: ${label}`);
}
function observe(value) {
    for (const hit of value.matchAll(/T(\d\d)@(\d{13})/g)) {
        const index = Number(hit[1]);
        const latency = Date.now() - Number(hit[2]);
        if (index >= 1 && index <= 24 && latency >= 0 && !stamps.has(index)) stamps.set(index, latency);
    }
    return value;
}

async function browser() {
    const launch = await wait("browser fixture handoff", () => serverText.match(/PRODUCT_FRONTEND_READY ([^\n]+)/)?.[1]);
    assert.ok(launch.startsWith(tmp + "/") && launch.endsWith("/frontend-launch.private"));
    const url = await readFile(launch, "utf8");
    assert.match(url, /^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/);
    const profile = join(root, "chrome");
    frontend = spawnOwned(chrome, [
        "--headless=new", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update",
        "--no-first-run", "--no-default-browser-check", "--remote-debugging-address=127.0.0.1",
        "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--force-renderer-accessibility",
        "--window-size=1100,820", "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "about:blank",
    ], { cwd: project, env, stdio: "ignore" });
    const address = await wait("private Chrome startup", async () =>
        (await readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => "")).match(/^(\d+)\n(\/devtools\/browser\/[^\n]+)/));
    cdp = new WebSocket(`ws://127.0.0.1:${address[1]}${address[2]}`);
    let id = 0, session, exceptions = 0, reads = 0;
    const pending = new Map();
    function send(method, params = {}) {
        return new Promise((done, fail) => {
            const key = ++id;
            const timer = setTimeout(() => { pending.delete(key); fail(new Error(`Owned CDP timed out: ${method}`)); }, 10_000);
            pending.set(key, message => {
                clearTimeout(timer);
                if (message.error) fail(new Error(`Owned CDP failed: ${method}`)); else done(message.result);
            });
            cdp.send(JSON.stringify({ id: key, method, params, ...(session ? { sessionId: session } : {}) }));
        });
    }
    cdp.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        if (message.method === "Runtime.exceptionThrown") exceptions++;
        if (message.method === "Network.webSocketFrameSent" &&
            message.params.response.payloadData.includes("readShellSessionOutput")) reads++;
        if (!pending.has(message.id)) return;
        const complete = pending.get(message.id);
        pending.delete(message.id); complete(message);
    });
    await wait("private CDP WebSocket", () => cdp.readyState === WebSocket.OPEN);
    const { targetId } = await send("Target.createTarget", { url: "about:blank" });
    session = (await send("Target.attachToTarget", { targetId, flatten: true })).sessionId;
    for (const domain of ["Accessibility", "Page", "Runtime", "Network"]) await send(`${domain}.enable`);
    const nodes = async () => (await send("Accessibility.getFullAXTree")).nodes.filter(node => !node.ignored);
    const names = async () => observe((await nodes()).map(node => node.name?.value).filter(Boolean).join("\n"));
    const title = (name, text = name) => wait(name, async () => (await names()).includes(text));
    async function click(label, filter = node => node.role?.value === "button" && node.name?.value === label, button = "left") {
        const target = await wait(label, async () => (await nodes()).find(node => node.backendDOMNodeId && filter(node)));
        const box = (await send("DOM.getBoxModel", { backendNodeId: target.backendDOMNodeId })).model.content;
        const x = (box[0] + box[4]) / 2, y = (box[1] + box[5]) / 2;
        await send("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button, buttons: button === "right" ? 2 : 1, clickCount: 1 });
        await send("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button, clickCount: 1 });
    }
    async function key(name, code) {
        for (const type of ["rawKeyDown", "keyUp"]) {
            await send("Input.dispatchKeyEvent", { type, key: name, code: name, windowsVirtualKeyCode: code });
        }
    }
    try {
        await send("Page.navigate", { url });
        await title("browser backend connected", "Backend: Connected");
        await click("Expand left sidebar");
        await title("browser terminals sidebar", "Terminal sessions");
        await click("new session composer", node => node.role?.value === "textbox" && node.name?.value === "Message");
        await send("Input.insertText", { text: "Run offline terminal inspector fixture" });
        const submitted = Date.now();
        await key("Enter", 13);
        await click("running shell row", node => /^(Shell session \d+)$/.test(node.name?.value ?? ""), "right");
        assert.equal(reads, 0, "Ordinary menu must not start polling");
        await click("View output");
        await title("browser output before initial yield", "FIXTURE_BOOT");
        beforeInitialYieldMs = Date.now() - submitted;
        assert.ok(beforeInitialYieldMs < 10_000, "The first visible batch must precede initial yield");
        await writeFile(join(tmp, "start-terminal-output"), "", { mode: 0o600 });
        await wait("browser visible output samples", async () => { await names(); return stamps.size === 24; });
        await title("browser final output", "FIXTURE_FINAL");
        await title("browser observed exit code", "Exit code 23");
        assert.ok(!(await names()).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
        await key("PageUp", 33);
        await click("Scroll to latest");
        await title("browser followed final output", "FIXTURE_FINAL");
        await click("Back");
        await wait("browser inspector releases without reopening retired row", async () => {
            const value = await names(); return !value.includes("Terminal output") && !value.includes("Close session");
        });
        assert.ok(reads > 0, "Production inspector must use the actual RPC read method");
        const drained = reads;
        await delay(425, undefined, { signal: stopped.signal });
        assert.equal(reads, drained, "Drained inspector must not keep polling");
        assert.equal(exceptions, 0, "Browser runtime exceptions");
    } finally {
        session = undefined;
        if (cdp.readyState === WebSocket.OPEN) await send("Browser.close").catch(() => {});
        cdp.close();
    }
}

async function tui(recordPath) {
    const record = JSON.parse(await readFile(recordPath, "utf8"));
    assert.ok(Number.isInteger(record.port) && record.port > 0 && record.port <= 65535);
    const backendHome = recordPath.slice(0, -"/.locks/backend/connection.json".length);
    const columns = 106, rows = 29;
    const pidFile = join(root, "tui.pid");
    const shell = join(root, "tui-launch");
    const socket = join(root, "mux.sock");
    const run = promisify(execFile);
    const tuiEnv = { ...env, PTY_PID_FILE: pidFile, PTY_CLI: binary, PTY_PORT: String(record.port), PTY_HOME: backendHome };
    const tmux = async (...args) => (await run("/usr/bin/tmux", ["-S", socket, "-f", "/dev/null", ...args],
        { cwd: project, env: tuiEnv, timeout: 5_000, maxBuffer: 65_536 })).stdout;
    await writeFile(shell, [
        "set -eu", 'umask 077; printf "%s" "$$" > "$PTY_PID_FILE"',
        'exec "$PTY_CLI" tui "--backend-url=http://127.0.0.1:$PTY_PORT" "--backend-home=$PTY_HOME"',
    ].join("\n"), { mode: 0o600 });
    tty = async () => {
        await tmux("kill-server").catch(() => {});
        const pid = Number(await readFile(pidFile, "utf8").catch(() => ""));
        if (!Number.isSafeInteger(pid) || pid <= 1) return;
        async function stillOurs() {
            const exe = await readlink(`/proc/${pid}/exe`).catch(() => "");
            const argv = await readFile(`/proc/${pid}/cmdline`, "utf8").catch(() => "");
            return exe === binary && argv.includes(`--backend-home=${backendHome}\0`);
        }
        if (await stillOurs()) process.kill(pid, "SIGTERM");
        for (let i = 0; i < 50 && await stillOurs(); i++) await delay(25);
        assert.ok(!await stillOurs(), "Private CLI still attached to PTY");
    };
    await tmux("new-session", "-d", "-s", "inspector", "-x", String(columns), "-y", String(rows), `exec /bin/sh ${shell}`);
    const pane = "inspector:0.0";
    let last = "";
    ttySnapshot = () => last;
    const keys = (...values) => tmux("send-keys", "-t", pane, ...values);
    const raw = text => keys("-H", ...Array.from(Buffer.from(text), byte => byte.toString(16).padStart(2, "0")));
    async function capture() { last = observe(await tmux("capture-pane", "-p", "-t", pane)); return last; }
    async function position(text) {
        const lines = (await capture()).split("\n");
        const y = lines.findIndex(line => line.includes(text));
        // The target labels precede any wide text on their row.
        return y < 0 ? null : { x: lines[y].indexOf(text) + 1, y: y + 1 };
    }
    const displayed = text => wait(`PTY visible ${text}`, () => position(text));
    async function click(text, button = 0) {
        const {x, y} = await displayed(text);
        await raw(`\x1b[<${button};${x};${y}M\x1b[<${button};${x};${y}m`);
    }
    await displayed("[+]");
    await keys("-l", "Run offline terminal inspector fixture");
    const submitted = Date.now();
    await keys("Enter");
    await click("[→]");
    await click("● exec /bin/sh", 2);
    await displayed("View output");
    await keys("Enter");
    await displayed("FIXTURE_BOOT");
    beforeInitialYieldMs = Date.now() - submitted;
    assert.ok(beforeInitialYieldMs < 10_000, "The first visible batch must precede initial yield");
    await writeFile(join(tmp, "start-terminal-output"), "", { mode: 0o600 });
    await wait("PTY visible output samples", async () => { await capture(); return stamps.size === 24; });
    await displayed("FIXTURE_FINAL");
    await displayed("Exit code 23");
    assert.ok(!(await capture()).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
    assert.ok(!(await tmux("display-message", "-p", "-t", pane, "#{pane_title}")).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
    await keys("PageUp");
    await click("[↓]");
    await displayed("FIXTURE_FINAL");
    await keys("Escape");
    await wait("PTY inspector releases without reopening retired row", async () => !await position("Terminal output"));
}

try {
    backend = spawnOwned(fixture, [], { cwd: project, env, stdio: ["pipe", "pipe", "ignore"] });
    backend.child.stdin.on("error", () => {});
    backend.child.stdout.on("data", bytes => { serverText = (serverText + bytes).slice(-8192); });
    const recordPath = await wait("private backend readiness", () => serverText.match(/PRODUCT_BROWSER_READY ([^\n;]+);/)?.[1]);
    assert.ok(recordPath.startsWith(tmp + "/") && recordPath.endsWith("/.locks/backend/connection.json"));
    if (mode === "browser") await browser(); else await tui(recordPath);
    const samples = [...stamps.values()].sort((a, b) => a - b);
    const p95 = samples[Math.ceil(samples.length * .95) - 1];
    console.log(JSON.stringify({ mode, samples: samples.length, p95, milliseconds: samples, beforeInitialYieldMs, finalExitCode: 23 }));
    assert.ok(p95 <= 500, `Visible output p95 exceeded 500 ms: ${p95}`);
} catch (error) {
    failure = error;
} finally {
    try {
        await cleanupSteps([
            async () => { if (backend?.child.stdin.writable) backend.child.stdin.end("\n"); },
            async () => { await tty?.(); cdp?.close(); },
            async () => { if (frontend) await stopOwned(frontend, { label: "Private frontend" }); },
            async () => {
                if (!backend) return;
                await Promise.race([backend.done, delay(5_000, undefined, { ref: false })]);
                await stopOwned(backend, { label: "Private backend", expectedCode: 0 });
            },
        ]);
        await rm(root, { recursive: true, force: true });
    } catch (error) { failure ??= error; }
}
if (failure) {
    console.error(`FAIL terminal inspector ${mode} at ${stage}: ${failure.message}`);
    if (ttySnapshot && process.env.KODEX_TERMINAL_SMOKE_DIAGNOSTIC === "1") console.error(ttySnapshot());
    process.exitCode = 1;
}

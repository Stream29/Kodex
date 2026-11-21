import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { chmod, mkdtemp, mkdir, readFile, readlink, realpath, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { cleanupSteps, privateEnvironment, spawnOwned, stopOwned } from "./local-entry-tools.mjs";

// Takes only build outputs. Every process, profile, backend Home and fixed shell fixture is private.
const [mode, fixturePath, binaryPath, scenario] = process.argv.slice(2);
const preview = scenario === "preview";
const intentScenario = preview ? undefined : scenario;
assert.ok(process.platform === "linux" && ["browser", "tui"].includes(mode),
    "Usage: node check-terminal-inspector.mjs browser|tui FIXTURE PRODUCTION_WEBSITE|NATIVE_CLI [preview|watch|no-watch|reopen]");
assert.ok(process.argv.length === 5 || process.argv.length === 6 &&
    (preview || ["watch", "no-watch"].includes(intentScenario) || mode === "browser" && intentScenario === "reopen"));
const intent = "Inspect fixture output";
const exitTitle = `${intent} (exit 23)`;
const commandMarker = "emit-terminal-inspector";
const fixture = await realpath(fixturePath);
const binary = await realpath(binaryPath);
const chrome = mode === "browser" ? await realpath(process.env.CHROME_BIN) : null;
const root = await mkdtemp(preview ? "/tmp/kodex-terminal-preview-" : "/tmp/kodex-terminal-inspector-");
await chmod(root, 0o700);
const home = join(root, "os-home"), tmp = join(root, "tmp"), project = join(root, "project"), runtime = join(root, "runtime");
for (const dir of [home, tmp, project, runtime]) await mkdir(dir, { mode: 0o700 });
const env = {
    ...privateEnvironment({ home, bin: root, runtime, temporary: root, capture: join(root, "unused") }),
    TERM: "xterm-256color", KODEX_CHECK_TERMINAL_INSPECTOR: "1",
    ...(intentScenario ? { KODEX_CHECK_COMMAND_INTENT: intentScenario } : {}),
    ...(mode === "browser" ? { KODEX_BROWSER_FIXTURE_WEBSITE: binary } : {}),
};
await writeFile(join(tmp, "emit-terminal-inspector"), [
    "#!/bin/sh",
    "set -eu",
    'printf "%s\\n" FIXTURE_BOOT',
    'i=0; while [ ! -f "$TMPDIR/start-terminal-output" ]; do',
    '  [ "$i" -lt 300 ] || exit 91; i=$((i+1)); sleep .1',
    "done",
    ...(preview ? [
        'i=1; while [ "$i" -le 24 ]; do printf "%s\\n" "PREVIEW_LINE_$i"; i=$((i+1)); done',
        'printf "%s\\\\n" PREVIEW_LIVE',
        'i=0; while [ ! -f "$TMPDIR/finish-terminal-preview" ]; do',
        '  [ "$i" -lt 650 ] || exit 92; i=$((i+1)); sleep .1',
        "done",
    ] : intentScenario ? [] : [
    "i=1; while [ \"$i\" -le 24 ]; do",
    '  stamp=$(/usr/bin/date +%s%3N)',
    '  printf "\\033[32mT%02d@%s\\033[0m\\n" "$i" "$stamp"',
    '  sleep .35; i=$((i+1))',
    "done",
    "sleep 2",
    "printf '\\033]0;NEVER_RENDER_TERMINAL_FIXTURE_TITLE\\007'",
    "awk 'BEGIN { for (i=0; i<9000; i++) printf \"load %05d %.48s\\n\", i, \"012345678901234567890123456789012345678901234567\" }'",
    ]),
    'printf "%s\\n" FIXTURE_FINAL',
    "exit 23",
].join("\n") + "\n", { mode: 0o600 });

const stopped = new AbortController();
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal,
    () => stopped.abort(new Error("Owned smoke interrupted")));
let stage = "fixture", frontend, backend, cdp, tty, ttySnapshot, browserSnapshot, serverText = "", failure, beforeInitialYieldMs;
let readCommits, evidence;
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

async function releaseFixture() {
    if (intentScenario) {
        await wait("initial call returns a registered background terminal", () => readCommits().some(({kind, value}) =>
            kind === "command_execution_tool_event" && value.call_id === "terminal-inspector-fixture-call" &&
            Number.isInteger(value.result?.value?.session_id)));
    }
    if (intentScenario === "watch") {
        await wait("terminal wait armed in the isolated Backend", () => readCommits().some(({kind, value}) =>
            kind === "unstable" && (Array.isArray(value) ? value : value.events ?? []).some(record =>
                record.call_id === "terminal-intent-await-call" && Number.isInteger(record.armed_for_agent_id))));
    }
    await writeFile(join(tmp, "start-terminal-output"), "", { mode: 0o600 });
}

async function checkSidebarPreview({
    screen, rowPresent, moveToRow, crossIntoPreview, scrollEarlierOutput, moveAway, primary, openMenu, openOutput, back, reads,
}) {
    const previewFields = value => [
        "Workdir: " + tmp, "Shell: /bin/sh", `exec /bin/sh ./${commandMarker}`, "PREVIEW_COMMAND_END",
    ].every(text => value.includes(text)) && /Terminal #[1-9]\d*/.test(value);
    const noPreview = value => !value.includes("PREVIEW_COMMAND_END") && !value.includes("Workdir: " + tmp);
    const isMenu = value => value.includes("View output") && value.includes("Close session") && /Terminal #[1-9]\d*/.test(value);
    const waitScreen = (label, predicate) => wait(label, async () => predicate(await screen()));
    const readCount = () => reads?.();
    async function expectNoMoreReads() {
        if (!reads) return;
        const previous = readCount();
        await delay(425, undefined, { signal: stopped.signal });
        assert.equal(readCount(), previous, "Closed preview/inspector must not keep polling");
    }

    assert.ok(noPreview(await screen()), "Preview must not mount without hover");
    await primary();
    assert.ok(!(await screen()).includes("View output"), "Primary click must not open a menu or inspector");
    await moveToRow();
    await waitScreen("delayed hover shows title, exact metadata, full command and the first output", value =>
        value.includes(intent) && previewFields(value) && value.includes("FIXTURE_BOOT") && value.includes("Reading output"));
    await crossIntoPreview();
    await waitScreen("preview remains under the pointer", value => previewFields(value) && value.includes("FIXTURE_BOOT"));
    if (reads) assert.ok(readCount() > 0, "Live hover must use actual Terminal output RPC");
    await releaseFixture();
    await waitScreen("a later process flush reaches the same hover", value => previewFields(value) && value.includes("PREVIEW_LIVE"));
    await scrollEarlierOutput();
    await waitScreen("hover output scrolls back from the live tail", value =>
        previewFields(value) && /PREVIEW_LINE_(?:[1-9]|1\d)\b/.test(value));
    await moveAway();
    await waitScreen("pointer exit dismisses preview", noPreview);
    await expectNoMoreReads();
    assert.ok(await rowPresent(), "Dismissing hover must not close the process");

    await openMenu();
    await waitScreen("compact Terminal menu", isMenu);
    const menuScreen = await screen();
    assert.ok(noPreview(menuScreen) && !menuScreen.includes(commandMarker) && !menuScreen.includes("PREVIEW_LIVE"),
        "Menu must not duplicate the preview or show live output");
    await expectNoMoreReads();
    await openOutput();
    await waitScreen("independent View output opens", value => value.includes("PREVIEW_LIVE") && value.includes("Back"));
    await back();
    await waitScreen("View output returns to the running process menu", value => isMenu(value) && noPreview(value));
    await back();
    await waitScreen("Escape dismisses the restored menu", value => !value.includes("Close session"));

    await moveAway();
    await moveToRow();
    await waitScreen("reopen preview against the same running process", value => previewFields(value) && value.includes("PREVIEW_LIVE"));
    await writeFile(join(tmp, "finish-terminal-preview"), "", { mode: 0o600 });
    await wait("process exit drops preview and the running row", async () => !await rowPresent() && noPreview(await screen()));
    await expectNoMoreReads();
    evidence = { liveOutput: true, stableRowEdge: true, scrolledHoverOutput: true, metadata: true, primaryNoAction: true, compactMenu: true,
        independentViewOutput: true, dismissOnExit: true, rpcStoppedAfterDismissal: reads ? true : null };
}

async function validateIntentCommits() {
    const commits = await wait("terminal history and both follow-up calls recorded", () => {
        const rows = readCommits();
        return rows.some(({kind, value}) => kind === "command_execution_tool_event" && value.call_id === "intent-quick-call") &&
            rows.some(({kind, value}) => kind === "invalid_tool_call" && value.call_id === "intent-invalid-call") &&
            (intentScenario !== "watch" || rows.some(({kind}) => kind === "event_terminal_exited")) && rows;
    });
    const events = commits.filter(({kind}) => kind === "event_terminal_exited").map(({value}) => value);
    assert.equal(events.length, intentScenario === "watch" ? 1 : 0);
    const call = commits.find(({kind, value}) =>
        kind === "command_execution_tool_event" && value.call_id === "terminal-inspector-fixture-call").value;
    assert.equal(call.action.arguments.name, intent);
    assert.ok(call.result.value.session_id > 0 && call.result.value.exit_code == null);
    if (events.length) {
        assert.equal(events[0].session_id, call.result.value.session_id);
        assert.equal(events[0].name, intent);
        assert.equal(events[0].exit_code, 23);
        assert.equal(events[0].command, call.action.arguments.cmd);
        const awaitCall = commits.find(({kind, value}) =>
            kind === "await_event_tool_event" && value.call_id === "terminal-intent-await-call");
        assert.equal(awaitCall?.value.arguments.name, "Wait for fixture exit");
    }
    const quick = commits.find(({value}) => value.call_id === "intent-quick-call").value;
    assert.equal(quick.result.value.exit_code, 0);
    assert.ok(quick.result.value.session_id == null, "Fast command must not return a background ID");
    const rejected = commits.find(({value}) => value.call_id === "intent-invalid-call").value;
    assert.match(rejected.message, /name/);
    await assert.rejects(readFile(join(tmp, "invalid-command-started")), { code: "ENOENT" });
    evidence = { terminalId: call.result.value.session_id, exitRows: events.length, quickExitCode: 0, missingNameStarted: false };
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
    const nodes = async () => {
        const current = (await send("Accessibility.getFullAXTree")).nodes.filter(node => !node.ignored);
        if (process.env.KODEX_TERMINAL_SMOKE_DIAGNOSTIC === "1") {
            browserSnapshot = current.map(node => `${node.role?.value} ${node.backendDOMNodeId ?? "-"} ${node.name?.value ?? ""}`)
                .filter(line => /History|Inspect fixture|Exit 23|terminal|Scroll to/.test(line)).slice(-45).join("\n");
        }
        return current;
    };
    const names = async () => observe((await nodes()).map(node => node.name?.value).filter(Boolean).join("\n"));
    const title = (name, text = name) => wait(name, async () => (await names()).includes(text));
    async function bounds(label, filter) {
        const target = await wait(label, async () => (await nodes()).find(node => node.backendDOMNodeId && filter(node)));
        const box = (await send("DOM.getBoxModel", { backendNodeId: target.backendDOMNodeId })).model.content;
        return { left: box[0], top: box[1], right: box[4], bottom: box[5] };
    }
    async function position(label, filter) {
        const box = await bounds(label, filter);
        return { x: (box.left + box.right) / 2, y: (box.top + box.bottom) / 2 };
    }
    async function click(label, filter = node => node.role?.value === "button" && node.name?.value === label, button = "left") {
        const { x, y } = await position(label, filter);
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
        if (intentScenario === "reopen") {
            const prefix = "intent-reopen-";
            const namesByCall = Object.fromEntries(readCommits()
                .filter(({value}) => value.call_id?.startsWith(prefix))
                .map(({value}) => [value.call_id.slice(prefix.length), value.action.arguments.name]));
            assert.deepEqual(namesByCall, {scan: "Scan recorded output", read: "Read recorded output"});
            await click("Sessions");
            await click("reopen Session", node => node.name?.value?.includes("Command intent reopen fixture"));
            await title("reopened History", "CURRENT_INTENT_REPLAY_READY");
            await click("open History group", node => node.role?.value === "button" && /^History group: Take/.test(node.name?.value ?? ""));
            const scan = node => node.role?.value === "button" &&
                /^(Expand History item|History tool): Scan recorded output$/.test(node.name?.value ?? "");
            const read = node => node.role?.value === "button" &&
                /^(Expand History item|History tool): Read recorded output$/.test(node.name?.value ?? "");
            await wait("two recorded command disclosures", async () => {
                const current = await nodes();
                return current.some(scan) && current.some(read);
            });
            assert.ok(!(await names()).includes("RECORDED_COMMAND_"));
            await click("open recorded command", read);
            await title("full recorded command is in details", "RECORDED_COMMAND_read");
            evidence = { replayedCurrentRows: 2 };
            return;
        }
        await title("browser terminals sidebar", "Terminal sessions");
        await click("new session composer", node => node.role?.value === "textbox" && node.name?.value === "Message");
        await send("Input.insertText", { text: "Run offline terminal inspector fixture" });
        const submitted = Date.now();
        await key("Enter", 13);
        await title("terminal row intent", intent);
        const isShellRow = node => /^Shell session \d+$/.test(node.name?.value ?? "");
        if (preview) {
            assert.equal(reads, 0, "Initial sidebar must not poll");
            const move = point => send("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
            let rowBounds;
            await checkSidebarPreview({
                screen: names, rowPresent: async () => (await nodes()).some(isShellRow),
                moveToRow: async () => {
                    rowBounds = await bounds("running shell row", isShellRow);
                    await move({ x: (rowBounds.left + rowBounds.right) / 2, y: (rowBounds.top + rowBounds.bottom) / 2 });
                },
                crossIntoPreview: async () => {
                    const row = rowBounds;
                    const from = { x: (row.left + row.right) / 2, y: (row.top + row.bottom) / 2 };
                    const metadata = () => bounds("Terminal preview metadata", node => /^Terminal #\d+$/.test(node.name?.value ?? ""));
                    const before = await metadata();
                    assert.ok(before.left >= 0 && before.right < row.left,
                        "Preview stays within the viewport, to the left of the right Terminal row");
                    await move({ x: from.x + 24, y: from.y });
                    await delay(60);
                    assert.deepEqual(await metadata(), before);
                    await move({ x: before.right - 2, y: from.y });
                    await delay(230);
                    assert.deepEqual(await metadata(), before);
                },
                scrollEarlierOutput: async () => {
                    const point = await position("live preview tail", node => node.name?.value === "PREVIEW_LIVE");
                    await move(point);
                    await send("Input.dispatchMouseEvent", { type: "mouseWheel", ...point, deltaX: 0, deltaY: -250 });
                },
                moveAway: () => move({ x: 0, y: 0 }),
                primary: () => click("primary click on running shell", isShellRow),
                openMenu: () => click("open running shell context menu", isShellRow, "right"),
                openOutput: () => click("View output"),
                back: () => key("Escape", 27), reads: () => reads,
            });
            assert.equal(exceptions, 0, "Browser runtime exceptions");
            return;
        }
        await click("running shell row", isShellRow, "right");
        assert.equal(reads, 0, "Ordinary menu must not start polling");
        await click("View output");
        await title("browser output before initial yield", "FIXTURE_BOOT");
        beforeInitialYieldMs = Date.now() - submitted;
        if (!intentScenario) assert.ok(beforeInitialYieldMs < 10_000, "The first visible batch must precede initial yield");
        await releaseFixture();
        if (!intentScenario) await wait("browser visible output samples", async () => { await names(); return stamps.size === 24; });
        await title("browser final output", "FIXTURE_FINAL");
        await title("browser observed exit code", "Exit code 23");
        assert.ok(!(await names()).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
        if (!intentScenario) {
            await key("PageUp", 33);
            await click("Scroll to latest");
            await title("browser followed final output", "FIXTURE_FINAL");
        } else {
            await wait("exited process leaves the browser running sidebar", async () =>
                !(await nodes()).some(node => /^Shell session \d+$/.test(node.name?.value ?? "")));
            await title("browser inspector retains the named title", intent);
            await validateIntentCommits();
        }
        await click("Back");
        await wait("browser inspector releases without reopening retired row", async () => {
            const value = await names(); return !value.includes("Exit code 23") && !value.includes("Close session");
        });
        if (intentScenario) {
            const exitButton = node => node.role?.value === "button" && node.name?.value === exitTitle;
            const toolButton = node => node.role?.value === "button" &&
                /^(Expand History item|History tool): Inspect fixture output$/.test(node.name?.value ?? "");
            async function reveal(label, filter) {
                const opened = new Set();
                await wait(label, async () => {
                    const current = await nodes();
                    if (current.some(node => node.backendDOMNodeId && filter(node))) return true;
                    const group = current.find(node => /^History group: Take \d+ actions?$/.test(node.name?.value ?? "") &&
                        node.backendDOMNodeId && !opened.has(node.backendDOMNodeId));
                    if (group) {
                        opened.add(group.backendDOMNodeId);
                        await click("open History group", node => node.backendDOMNodeId === group.backendDOMNodeId);
                    }
                    return false;
                });
            }
            await reveal("browser History has the named call disclosure", toolButton);
            assert.ok(!(await names()).includes(commandMarker), "Collapsed History must hide the command");
            if (intentScenario === "watch") {
                await reveal("browser History has the recorded exit disclosure", exitButton);
                await click("open recorded exit", exitButton);
                await title("browser recorded exit reveals the command", commandMarker);
                await click("collapse recorded exit", exitButton);
                await wait("browser recorded exit hides the command again", async () => !(await names()).includes(commandMarker));
            } else assert.ok(!(await nodes()).some(exitButton));
            await reveal("browser History restores the named tool after exit disclosure", toolButton);
            await click("open named History tool", toolButton);
            await title("browser History reveals the command", commandMarker);
            await click("collapse named History tool", toolButton);
            await wait("browser History hides command again", async () => !(await names()).includes(commandMarker));
        }
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
    if (preview) {
        await displayed("● Inspect fixture");
        const move = (x, y) => raw(`\x1b[<35;${x};${y}M`);
        await checkSidebarPreview({
            screen: capture, rowPresent: async () => Boolean(await position("● Inspect fixture")),
            moveToRow: async () => { const { x, y } = await displayed("● Inspect fixture"); await move(x, y); },
            crossIntoPreview: async () => {
                const from = await displayed("● Inspect fixture");
                const before = await displayed("Terminal #");
                const mainColumns = columns - Math.floor(columns / 4);
                const terminalStart = mainColumns + 1; // Zero-based, after the splitter.
                const previewWidth = Math.min(mainColumns,
                    Math.max(36, `Workdir: ${tmp}`.length, `exec /bin/sh ./${commandMarker}`.length));
                assert.equal(from.x, terminalStart + 1, "The running row is in the right Terminal tool window");
                assert.equal(before.x + previewWidth, terminalStart, "Preview ends before the one-cell splitter");
                await move(from.x + 5, from.y);
                await delay(60);
                assert.deepEqual(await position("Terminal #"), before);
                await move(before.x + previewWidth - 1, from.y);
                await delay(230);
                assert.deepEqual(await position("Terminal #"), before);
            },
            scrollEarlierOutput: async () => {
                const { x, y } = await displayed("PREVIEW_LIVE");
                await move(x, y);
                for (let i = 0; i < 4; i++) await raw(`\x1b[<64;${x};${y}M`);
            },
            moveAway: () => move(columns, rows),
            primary: () => click("● Inspect fixture"),
            openMenu: () => click("● Inspect fixture", 2),
            openOutput: () => keys("Enter"), back: () => keys("Escape"),
        });
        return;
    }
    await click("● Inspect fixture", 2);
    await displayed("View output");
    await keys("Enter");
    await displayed("FIXTURE_BOOT");
    beforeInitialYieldMs = Date.now() - submitted;
    if (!intentScenario) assert.ok(beforeInitialYieldMs < 10_000, "The first visible batch must precede initial yield");
    await releaseFixture();
    if (!intentScenario) await wait("PTY visible output samples", async () => { await capture(); return stamps.size === 24; });
    await displayed("FIXTURE_FINAL");
    await displayed("Exit code 23");
    assert.ok(!(await capture()).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
    assert.ok(!(await tmux("display-message", "-p", "-t", pane, "#{pane_title}")).includes("NEVER_RENDER_TERMINAL_FIXTURE_TITLE"));
    if (!intentScenario) {
        await keys("PageUp");
        await click("[↓]");
        await displayed("FIXTURE_FINAL");
    } else {
        await wait("exited process leaves the PTY running sidebar", async () => !(await capture()).includes("● Inspect fixture"));
        await displayed(intent);
        await validateIntentCommits();
    }
    await keys("Escape");
    await wait("PTY inspector releases without reopening retired row", async () => !await position("Exit code 23"));
    if (intentScenario) {
        await wait("PTY History has intent disclosures", async () => {
            const snapshot = await capture();
            if (snapshot.includes(`> ${intent}`) && (intentScenario !== "watch" || snapshot.includes(exitTitle))) return true;
            const groups = snapshot.split("\n").filter(line => /> Take \d+ actions?/.test(line));
            if (groups.length) await click(/> Take \d+ actions?/.exec(groups.at(-1))[0]);
            return false;
        });
        assert.ok(!(await capture()).includes(commandMarker), "Collapsed History must hide the command");
        await click(`> ${intent}`);
        await displayed(commandMarker);
        await click(`v ${intent}`);
        await wait("PTY History hides command again", async () => !await position(commandMarker));
        if (intentScenario === "watch") {
            await click(exitTitle);
            await displayed(commandMarker);
        } else assert.ok(!await position(exitTitle));
    }
}

try {
    if (preview) await writeFile(join(tmp, "terminal-sidebar-preview"), "", { mode: 0o600 });
    backend = spawnOwned(fixture, [], { cwd: project, env, stdio: ["pipe", "pipe", "ignore"] });
    backend.child.stdin.on("error", () => {});
    backend.child.stdout.on("data", bytes => { serverText = (serverText + bytes).slice(-8192); });
    const recordPath = await wait("private backend readiness", () => serverText.match(/PRODUCT_BROWSER_READY ([^\n;]+);/)?.[1]);
    assert.ok(recordPath.startsWith(tmp + "/") && recordPath.endsWith("/.locks/backend/connection.json"));
    if (intentScenario) {
        const { DatabaseSync } = await import("node:sqlite");
        const databasePath = join(recordPath.slice(0, -"/.locks/backend/connection.json".length), "kodex.db");
        readCommits = () => {
            const db = new DatabaseSync(databasePath, { readOnly: true });
            try {
                return db.prepare("SELECT type, payload_json FROM agent_commits ORDER BY \"index\", type").all()
                    .map(row => ({kind: row.type, value: JSON.parse(row.payload_json)}));
            } finally { db.close(); }
        };
    }
    if (mode === "browser") await browser(); else await tui(recordPath);
    if (preview) {
        console.log(JSON.stringify({ mode, scenario, ...evidence }));
    } else if (intentScenario === "reopen") {
        console.log(JSON.stringify({ mode, intentScenario, ...evidence }));
    } else if (intentScenario) {
        console.log(JSON.stringify({ mode, intentScenario, intent, outputExitCode: 23, ...evidence }));
    } else {
        const samples = [...stamps.values()].sort((a, b) => a - b);
        const p95 = samples[Math.ceil(samples.length * .95) - 1];
        console.log(JSON.stringify({ mode, samples: samples.length, p95, milliseconds: samples, beforeInitialYieldMs, finalExitCode: 23 }));
        assert.ok(p95 <= 500, `Visible output p95 exceeded 500 ms: ${p95}`);
    }
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
    console.error(`FAIL terminal ${preview ? "sidebar preview" : "inspector"} ${mode} at ${stage}: ${failure.message}`);
    if (ttySnapshot && process.env.KODEX_TERMINAL_SMOKE_DIAGNOSTIC === "1") console.error(ttySnapshot());
    if (browserSnapshot) console.error(browserSnapshot);
    process.exitCode = 1;
}

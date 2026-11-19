import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, realpath, rm, symlink } from "node:fs/promises";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { cleanupSteps, privateEnvironment, runOwnedCommand, spawnOwned, stopOwned } from "./local-entry-tools.mjs";

const [mode, fixturePath, cliPath, sitePath] = process.argv.slice(2);
assert.ok(process.platform === "linux" && ["tui", "browser"].includes(mode) &&
    process.argv.length === (mode === "browser" ? 6 : 5),
    "Usage: node check-subagent-navigation.mjs tui|browser FIXTURE CLI [WEBSITE]");
const fixture = await realpath(fixturePath), cli = await realpath(cliPath);
const site = mode === "browser" ? await realpath(sitePath) : null;
const chrome = site && await realpath(process.env.CHROME_BIN);
const run = promisify(execFile);
const root = await mkdtemp("/tmp/kodex-subagent-navigation-");
const tmp = join(root, "tmp"), home = join(tmp, "home"), kodexHome = join(home, ".kodex");
const nav = join(tmp, "nav"), work = join(root, "work"), socket = join(root, "tmux.sock");
const env = {
    ...privateEnvironment({ home, bin: nav, runtime: join(root, "runtime"), temporary: root, capture: join(root, "unused") }),
    USER: "fixture", TERM: "xterm-256color", KODEX_HOME: kodexHome,
    KODEX_BROWSER_FIXTURE_CLI_HOME: "1", KODEX_CHECK_SUBAGENT_NAVIGATION: "1",
    ...(site ? { KODEX_BROWSER_FIXTURE_WEBSITE: site } : {}),
};
const abort = new AbortController();
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => abort.abort(new Error(`Interrupted: ${signal}`)));
let backend, browser, cdp, tmux, failure, diagnostic = "", logs = "", db;
const children = [];

async function wait(label, read, ms = 22_000) {
    const deadline = Date.now() + ms;
    while (Date.now() < deadline) {
        abort.signal.throwIfAborted();
        assert.equal(backend?.result ?? null, null, "isolated Backend exited");
        const value = await read();
        if (value) return value;
        await delay(40, undefined, { signal: abort.signal });
    }
    throw new Error(`Timed out: ${label}`);
}

async function command(...args) {
    const text = await runOwnedCommand(cli, args.map(String), { cwd: work, env, stdio: ["ignore", "pipe", "pipe"] },
        { signal: abort.signal, children, termMs: 8_000, killAfterMs: 11_000 });
    const result = JSON.parse(text);
    assert.equal(result.ok, true);
    return result.result;
}

async function makeBrowser() {
    const profile = join(root, "chrome");
    browser = spawnOwned(chrome, [
        "--headless=new", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update",
        "--no-first-run", "--no-default-browser-check", "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
        `--user-data-dir=${profile}`, "--force-renderer-accessibility", "--window-size=1150,870",
        "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "about:blank",
    ], { cwd: work, env, stdio: "ignore" });
    const address = await wait("private Chrome CDP", async () =>
        (await readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""))
            .match(/^(\d+)\n(\/devtools\/browser\/[^\n]+)/));
    cdp = new WebSocket(`ws://127.0.0.1:${address[1]}${address[2]}`);
    let seq = 0, session;
    const pending = new Map();
    function send(method, params = {}) {
        return new Promise((resolve, reject) => {
            const id = ++seq;
            const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 5000);
            pending.set(id, ({ error, result }) => {
                clearTimeout(timeout);
                if (error) reject(new Error(`CDP error: ${method}`)); else resolve(result);
            });
            cdp.send(JSON.stringify({ id, method, params, ...(session ? { sessionId: session } : {}) }));
        });
    }
    cdp.addEventListener("message", event => {
        const response = JSON.parse(event.data);
        const receive = pending.get(response.id);
        pending.delete(response.id);
        receive?.(response);
    });
    await wait("CDP socket open", () => cdp.readyState === WebSocket.OPEN);
    await wait("browser handoff", () => logs.includes("PRODUCT_FRONTEND_READY "));
    const url = await readFile(join(kodexHome, "frontend-launch.private"), "utf8");
    assert.match(url, /^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/);
    const { targetId } = await send("Target.createTarget", { url: "about:blank" });
    session = (await send("Target.attachToTarget", { targetId, flatten: true })).sessionId;
    await send("Accessibility.enable");
    await send("Page.navigate", { url });
    async function nodes() {
        const tree = (await send("Accessibility.getFullAXTree")).nodes.filter(node => !node.ignored);
        diagnostic = tree.filter(node => node.name?.value || node.value?.value)
            .map(node => `${node.role?.value}: ${node.name?.value ?? ""} ${node.value?.value ?? ""}`).join("\n");
        return tree;
    }
    const inSidebar = (item, byId) => {
        for (let node = item; node; node = byId.get(node.parentId)) {
            if (node.name?.value === "Left sidebar") return true;
        }
        return false;
    };
    async function buttonNode(text) {
        return wait(`browser control ${text}`, async () => (await nodes()).find(node =>
            node.backendDOMNodeId && node.name?.value === text && ["button", "textbox"].includes(node.role?.value)));
    }
    async function key(name, code) {
        for (const type of ["rawKeyDown", "keyUp"]) await send("Input.dispatchKeyEvent",
            { type, key: name, code: name, windowsVirtualKeyCode: code });
    }
    async function center(node) {
        const model = (await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId })).model.content;
        return { x: (model[0] + model[4]) / 2, y: (model[1] + model[5]) / 2 };
    }
    async function clickPoint({ x, y }) {
        await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
        for (const type of ["mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent",
            { type, x, y, button: "left", buttons: type === "mouseReleased" ? 0 : 1, clickCount: 1 });
    }
    const click = async text => clickPoint(await center(await buttonNode(text)));
    await wait("browser connected", async () => { await nodes(); return diagnostic.includes("Backend: Connected"); });
    return {
        async type(text) { await click("Message"); await send("Input.insertText", { text }); },
        enter: () => key("Enter", 13),
        async showIndex() { await click("Expand left sidebar"); },
        async enterChild(_label, index) {
            const point = await wait(`browser Enter button for index ${index}`, async () => {
                const tree = await nodes();
                const row = tree.find(node => node.name?.value === `History index ${index}` && node.backendDOMNodeId);
                if (!row) return null;
                const rowCenter = await center(row);
                for (const node of tree) {
                    if (node.name?.value !== "Enter subagent" || node.role?.value !== "button" || !node.backendDOMNodeId) continue;
                    const button = await center(node);
                    if (button.x > rowCenter.x && Math.abs(button.y - rowCenter.y) < 4) return button;
                }
                return null;
            });
            await clickPoint(point);
            await send("Input.dispatchMouseEvent", { type: "mouseMoved", x: 500, y: 12 });
        },
        async menu(_text, index) {
            const node = await wait(`browser index ${index}`, async () => (await nodes()).find(node =>
                node.name?.value === `History index ${index}` && node.backendDOMNodeId));
            const box = (await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId })).model.content;
            const x = box[0] + 12, y = (box[1] + box[5]) / 2;
            await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
            await send("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "right", buttons: 2, clickCount: 1 });
            await send("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "right", clickCount: 1 });
        },
        click,
        clickHistory: click,
        async visible(text) { await nodes(); return diagnostic.includes(text); },
        async hasOneSessionTab() {
            return (await nodes()).filter(node => node.role?.value === "tab" && node.name?.value?.includes("Session tab:")).length === 1;
        },
        async inHistory(text) {
            const tree = await nodes();
            const byId = new Map(tree.map(item => [item.nodeId, item]));
            return tree.some(item => item.name?.value?.includes(text) && !inSidebar(item, byId));
        },
        async close() {
            session = undefined;
            if (cdp.readyState === WebSocket.OPEN) await send("Browser.close").catch(() => {});
        },
    };
}

async function makeTui() {
    const metadata = JSON.parse(await readFile(join(kodexHome, ".locks/backend/connection.json"), "utf8"));
    assert.ok(Number.isInteger(metadata.port) && metadata.port > 0 && metadata.port <= 65535);
    tmux = async (...args) => (await run("/usr/bin/tmux", ["-S", socket, "-f", "/dev/null", ...args],
        { cwd: work, env, timeout: 5000, maxBuffer: 131072 })).stdout;
    await tmux("new-session", "-d", "-s", "navigation", "-x", "130", "-y", "36", cli,
        "tui", `--backend-url=http://127.0.0.1:${metadata.port}`, `--backend-home=${kodexHome}`);
    const pane = "navigation:0.0";
    const keys = (...args) => tmux("send-keys", "-t", pane, ...args);
    async function capture() { diagnostic = await tmux("capture-pane", "-p", "-t", pane); return diagnostic; }
    const raw = text => keys("-H", ...Array.from(Buffer.from(text), byte => byte.toString(16).padStart(2, "0")));
    async function click(text, button = 0, sidebarOnly = false) {
        const { x, y } = await wait(`PTY control ${text}`, async () => {
            const lines = (await capture()).split("\n");
            const y = lines.findIndex(line => {
                const x = line.indexOf(text);
                return x >= 0 && (!sidebarOnly || x < 30);
            });
            return y < 0 ? null : { x: lines[y].indexOf(text) + 1, y: y + 1 };
        });
        await raw(`\x1b[<${button};${x};${y}M\x1b[<${button};${x};${y}m`);
    }
    await wait("TUI new Session", async () => (await capture()).includes("[+]"));
    return {
        type: text => keys("-l", text),
        enter: () => keys("Enter"),
        async showIndex() {
            await click("[→]");
            await wait("PTY History Index", async () => (await capture()).includes("[History Index]"));
            await delay(350);
        },
        async enterChild(label) {
            const { x, y } = await wait(`PTY Enter button for ${label}`, async () => {
                const lines = (await capture()).split("\n");
                const y = lines.findIndex(line => {
                    const start = line.indexOf(label);
                    const button = line.indexOf("[Enter]", start);
                    return start >= 0 && button > start && button < 30;
                });
                return y < 0 ? null : { x: lines[y].indexOf("[Enter]") + 2, y: y + 1 };
            });
            await raw(`\x1b[<0;${x};${y}M\x1b[<0;${x};${y}m`);
        },
        async menu(text) {
            await click(text, 2, true);
            await delay(100);
            if ((await capture()).includes("Revert to here")) {
                await keys("Escape");
                await click(text, 0, true);
                await raw("\x1b[21;2~");
            }
        },
        click,
        async clickHistory(text) {
            const { x, y } = await wait(`PTY History control ${text}`, async () => {
                const lines = (await capture()).split("\n");
                const y = lines.findIndex(line => line.indexOf(text, 30) >= 0);
                return y < 0 ? null : { x: lines[y].indexOf(text, 30) + 1, y: y + 1 };
            });
            await raw(`\x1b[<0;${x};${y}M\x1b[<0;${x};${y}m`);
        },
        async visible(text) { return (await capture()).includes(text); },
        async hasOneSessionTab() {
            return ((await capture()).split("\n")[0].match(/\[Session \d+]/g) ?? []).length === 1;
        },
        async inHistory(text) { return (await capture()).split("\n").some(line => line.indexOf(text, 30) >= 0); },
        async close() { await tmux("kill-server"); tmux = undefined; },
    };
}

try {
    for (const path of [home, nav, work, env.XDG_RUNTIME_DIR]) await mkdir(path, { recursive: true, mode: 0o700 });
    await symlink(cli, join(nav, "kodex"));
    backend = spawnOwned(fixture, [], { cwd: work, env, stdio: ["pipe", "pipe", "pipe"] });
    backend.child.stdin.on("error", () => {});
    backend.child.stdout.on("data", bytes => { logs = (logs + bytes).slice(-8192); });
    backend.child.stderr.resume();
    await wait("private Backend", () => logs.includes("PRODUCT_BROWSER_READY "));
    const ui = await (site ? makeBrowser() : makeTui());
    const show = text => wait(`${mode} visible: ${text}`, () => ui.visible(text));
    await ui.type("NAV_FIXTURE_ROOT");
    await ui.enter();
    await show("NAV_ROOT_READY");
    const spawnResult = async name => wait(`CLI spawn ${name}`, async () => {
        const text = await readFile(join(nav, `${name}.json`), "utf8").catch(() => "");
        return text && JSON.parse(text);
    });
    const a = (await spawnResult("a")).result, c = (await spawnResult("c")).result;
    assert.ok(a.submitted && c.submitted && c.parent_agent_id === a.agent_id);
    const rootId = a.parent_agent_id;
    assert.notEqual(rootId, a.session_id);
    assert.equal(c.session_id, a.session_id);
    const sessionCount = async () => {
        assert.equal((await command("session", "list")).sessions.length, 1);
        await wait("exactly one visible Session tab", () => ui.hasOneSessionTab());
    };
    await sessionCount();
    db = new DatabaseSync(join(kodexHome, "kodex.db"), { readOnly: true });
    const onlyC = () => db.prepare("SELECT agent_id FROM agent_commits WHERE type='user_message' AND payload_json LIKE ?")
        .all("%NAV_ONLY_C%").map(row => row.agent_id);
    const completeRow = () => db.prepare("SELECT count(*) AS n FROM agent_commits WHERE agent_id=? AND type='event_subagent_completed' AND json_extract(payload_json, '$.child_agent_id')=?")
        .get(a.agent_id, c.agent_id).n;
    const completionIndex = () => db.prepare('SELECT "index" AS n FROM agent_commits WHERE agent_id=? AND type=\'event_subagent_completed\' AND json_extract(payload_json, \'$.child_agent_id\')=?')
        .get(a.agent_id, c.agent_id).n;
    const creation = child => db.prepare('SELECT "index" AS n FROM agent_commits WHERE agent_id=? AND type=\'event_subagent_created\' AND json_extract(payload_json, \'$.child_agent_id\')=?')
        .get(child.parent_agent_id, child.agent_id).n;
    const createdC = "Subagent Worker-c created";
    const completedC = `Subagent #${c.agent_id} completed`;
    await ui.showIndex();
    await ui.menu("Worker-a", creation(a));
    await ui.click("Check out");
    await show("NAV_FIXTURE_ROOT");
    assert.ok(!await ui.visible("Return to creation"));
    async function enter(label, child, marker) {
        await ui.enterChild(label, creation(child)); await show(marker);
    }
    await enter("Worker-a", a, "NAV_A_READY");
    await ui.type("NAV_DRAFT_A");
    await enter("Worker-c", c, "NAV_C_READY");
    assert.ok(!await ui.visible("NAV_DRAFT_A"));
    await ui.type("NAV_ONLY_C");
    await ui.enter();
    await wait("exactly C received the message", () => onlyC().length > 0);
    assert.deepEqual(onlyC(), [c.agent_id]);
    await ui.click("Return to creation");
    await show("NAV_DRAFT_A");
    await wait("parent creation appears in History after return", () => ui.inHistory(createdC));
    assert.ok(!await ui.inHistory("NAV_FIXTURE_C"));
    await ui.clickHistory(createdC);
    await wait("the delegated prompt is visible below the spawn heading", () => ui.inHistory("NAV_FIXTURE_C"));
    await ui.clickHistory(createdC);
    await wait("the delegated prompt is hidden again", async () => !await ui.inHistory("NAV_FIXTURE_C"));
    assert.equal((await command("agent", "status", "--agent", c.agent_id)).running, true);
    await enter("Worker-c", c, "NAV_ONLY_C");
    assert.equal(completeRow(), 0);
    await show("Return to creation");
    assert.ok(!await ui.visible("Return to completion"));
    await command("agent", "stop", "--agent", c.agent_id);
    const completion = await command("agent", "complete", "--agent", c.agent_id);
    assert.ok(completion.completed);
    await wait("parent consumed one completion", () => completeRow() === 1);
    await ui.click("Return to completion");
    await show("NAV_DRAFT_A");
    await wait("parent completion appears in History after return", () => ui.inHistory(completedC));
    await ui.menu(completedC, completionIndex());
    await ui.click("Check out");
    await wait("completion index menu closes", async () => !await ui.visible("Check out"));
    await ui.click("Return to creation");
    await show("NAV_ROOT_READY");
    assert.ok(!await ui.visible("NAV_DRAFT_A"));
    await sessionCount();
    await ui.close();
    console.log(JSON.stringify({ mode, session: a.session_id, path: [rootId, a.agent_id, c.agent_id],
        oneSession: true, draftRetained: true, returnedWhileRunning: true, completedAtParent: true }));
} catch (error) {
    if (process.env.KODEX_NAVIGATION_DIAGNOSTIC === "1") console.error(diagnostic);
    failure = error;
} finally {
    await cleanupSteps([
        async () => db?.close(),
        async () => { if (tmux) await tmux("kill-server").catch(() => {}); },
        async () => cdp?.close(),
        async () => { if (browser) await stopOwned(browser, { label: "Private Chrome" }); },
        ...children.map(child => () => stopOwned(child, { label: "Private CLI" })),
        async () => {
            if (!backend) return;
            backend.child.stdin.end("\n");
            await Promise.race([backend.done, delay(2500, null, { ref: false })]);
            await stopOwned(backend, { label: "Private Backend" });
        },
        () => rm(root, { recursive: true, force: true }),
    ], failure);
}

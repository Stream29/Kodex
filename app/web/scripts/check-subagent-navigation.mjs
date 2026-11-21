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
let backend, browser, cdp, tmux, failure, diagnostic = "", logs = "", backendErrors = "", db;
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
        const tree = (await send("Accessibility.getFullAXTree")).nodes;
        diagnostic = tree.filter(node => !node.ignored && (node.name?.value || node.value?.value))
            .map(node => `${node.role?.value}: ${node.name?.value ?? ""} ${node.value?.value ?? ""}`).join("\n");
        return tree;
    }
    const inside = (item, ancestor, byId) => {
        for (let node = item; node; node = byId.get(node.parentId)) {
            if (node.nodeId === ancestor.nodeId) return true;
        }
        return false;
    };
    const control = (tree, name) => tree.find(node =>
        node.backendDOMNodeId && node.name?.value === name && ["button", "textbox"].includes(node.role?.value));
    async function key(name, code) {
        for (const type of ["rawKeyDown", "keyUp"]) await send("Input.dispatchKeyEvent",
            { type, key: name, code: name, windowsVirtualKeyCode: code });
    }
    async function bounds(node) {
        if (!node?.backendDOMNodeId) return null;
        const { model } = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }).catch(error => {
            if (error.message !== "CDP error: DOM.getBoxModel") throw error;
            return {}; // Compose may have just replaced the AX mirror node.
        });
        if (!model?.width || !model.height) return null;
        const box = model.content;
        return { left: box[0], top: box[1], right: box[4], bottom: box[5] };
    }
    async function center(node) {
        const rect = await bounds(node);
        return rect && { x: (rect.left + rect.right) / 2, y: (rect.top + rect.bottom) / 2 };
    }
    const within = (point, region) => point && region &&
        point.x >= region.left && point.x < region.right && point.y >= region.top && point.y < region.bottom;
    async function clickPoint({ x, y }, button = "left") {
        await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
        for (const type of ["mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent",
            { type, x, y, button, buttons: type === "mouseReleased" ? 0 : button === "right" ? 2 : 1, clickCount: 1 });
    }
    async function leaveIndex() {
        let x = 500;
        const move = () => send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y: 12 });
        await move();
        let clearSince;
        await wait("browser Index hover dismissed", async () => {
            const tree = await nodes();
            if (diagnostic.includes("Index: ") || !tree.some(node => !node.ignored && node.name?.value === "Agent page history")) {
                clearSince = undefined;
                x = x === 500 ? 520 : 500;
                await move();
                return false;
            }
            // A tab switch can queue a new hover after one clear frame (300 ms
            // preview delay and 180 ms exit grace).
            clearSince ??= performance.now();
            return performance.now() - clearSince >= 500;
        });
    }
    const click = async text => {
        const name = ({
            "Close Agent tab": "Application menu action: close",
            "[↓]": "Scroll to latest",
        }[text] ?? text);
        await clickPoint(await wait(`browser control ${name}`, async () => center(control(await nodes(), name))));
    };
    const tabNodes = tree => tree.filter(node => node.role?.value === "tab" && /Session tab: tab-\d+/.test(node.name?.value));
    const tabName = node => node.name.value.split(", Session tab:")[0].trim();
    const tabId = node => node.name.value.match(/Session tab: (tab-\d+)/)[1];
    const rightSidebar = tree => tree.find(node => node.name?.value === "Right sidebar");
    const rowIndex = node => !node.ignored && node.backendDOMNodeId
        ? /^History index (\d+)$/.exec(node.name?.value ?? "")?.[1] : undefined;
    const indexRow = (tree, index) => tree.find(node => rowIndex(node) === String(index));
    function indexMenuMatches(tree, index) {
        if (!control(tree, "Go to")) return false;
        const indexes = [...new Set(tree.filter(node => !node.ignored && node.role?.value === "StaticText")
            .map(node => /^Index: (\d+)$/.exec(node.name?.value ?? ""))
            .filter(Boolean).map(match => Number(match[1])))];
        if (indexes.length === 0) return false;
        assert.deepEqual(indexes, [index], "Right History Index menu must target the requested row");
        return true;
    }
    async function historyPoint(text, disclosure = false) {
        const tree = await nodes();
        const history = tree.find(node => node.name?.value === "Agent page history");
        const region = await bounds(history);
        if (!region) return null;
        const byId = new Map(tree.map(node => [node.nodeId, node]));
        for (const node of tree) {
            if (!node.backendDOMNodeId || node.ignored || !inside(node, history, byId)) continue;
            const name = node.name?.value;
            if (disclosure ? node.role?.value !== "button" || name !== text : !name?.includes(text)) continue;
            const rect = await bounds(node);
            if (!rect) continue;
            const point = {
                // Click the real disclosure near its left edge, not near the
                // Index side where a sidebar preview can overlay the wide row.
                x: disclosure ? rect.left + Math.min(24, (rect.right - rect.left) / 2) : (rect.left + rect.right) / 2,
                y: (rect.top + rect.bottom) / 2,
            };
            if (within(point, region)) return point;
        }
        return null;
    }
    await wait("browser connected", async () => { await nodes(); return diagnostic.includes("Backend: Connected"); });
    return {
        leaveIndex,
        async type(text) { await click("Message"); await send("Input.insertText", { text }); },
        enter: () => key("Enter", 13),
        async showIndex(index) {
            const tree = await nodes();
            if (!await bounds(rightSidebar(tree))) await click("History Index");
            await wait(`browser right History Index: ${index}`, async () => {
                const tree = await nodes();
                return within(await center(indexRow(tree, index)), await bounds(rightSidebar(tree)));
            });
        },
        async enterChild(_label, index) {
            const point = await wait(`browser Enter button for index ${index}`, async () => {
                const tree = await nodes();
                const sidebar = await bounds(rightSidebar(tree));
                const rowCenter = await center(indexRow(tree, index));
                if (!within(rowCenter, sidebar)) return null;
                for (const node of tree) {
                    if (node.name?.value !== "Enter subagent" || node.role?.value !== "button" || !node.backendDOMNodeId) continue;
                    const button = await center(node);
                    if (within(button, sidebar) && button.x > rowCenter.x && Math.abs(button.y - rowCenter.y) < 4) return button;
                }
                return null;
            });
            await clickPoint(point);
            await send("Input.dispatchMouseEvent", { type: "mouseMoved", x: 500, y: 12 });
        },
        async menu(_text, index) {
            await leaveIndex();
            let lastRange, wheelCount = 0;
            const point = await wait(`browser index ${index}`, async () => {
                const tree = await nodes();
                const sidebar = await bounds(rightSidebar(tree));
                if (!sidebar) return null;
                const point = await center(indexRow(tree, index));
                if (within(point, sidebar)) return point;
                const visible = [];
                for (const node of tree) {
                    const row = rowIndex(node);
                    if (row !== undefined && within(await center(node), sidebar)) visible.push(Number(row));
                }
                if (visible.length === 0) return null;
                const first = Math.min(...visible), last = Math.max(...visible);
                const direction = index < first ? -1 : index > last ? 1 : 0;
                const range = `${first}..${last}`;
                if (!direction || range === lastRange) return null;
                assert.ok(++wheelCount <= 6, `Right Index ${index} is still outside the measured range ${range}`);
                lastRange = range;
                // Move only the right list, in the direction implied by its placed rows.
                const x = (sidebar.left + sidebar.right) / 2, y = (sidebar.top + sidebar.bottom) / 2;
                await send("Input.dispatchMouseEvent", { type: "mouseWheel", x, y, deltaX: 0, deltaY: direction * 128 });
                await send("Input.dispatchMouseEvent", { type: "mouseMoved", x: 500, y: 12 });
                return null;
            });
            await clickPoint(point, "right");
        },
        click,
        async indexMenu(index) { return indexMenuMatches(await nodes(), index); },
        async followingLatest() { await nodes(); return !diagnostic.includes("Scroll to latest"); },
        async revertAndEdit(expectedIndex) {
            const point = await wait(`right History Index ${expectedIndex} offers Revert and edit`, async () => {
                const tree = await nodes();
                return indexMenuMatches(tree, expectedIndex) && await center(control(tree, "Revert and edit"));
            });
            console.log(`browser: verified Revert and edit at right History Index ${expectedIndex}`);
            await clickPoint(point);
        },
        async visible(text) { await nodes(); return diagnostic.includes(text); },
        async tabs() {
            return tabNodes(await nodes()).map(node => ({
                label: tabName(node), id: tabId(node),
                selected: node.properties?.some(property => property.name === "selected" && property.value.value === true),
            }));
        },
        async tab(label, menu = false) {
            const point = await wait(`browser Agent tab ${label}`, async () =>
                center(tabNodes(await nodes()).find(node => tabName(node) === label)));
            await clickPoint(point, menu ? "right" : "left");
        },
        async inHistory(text) { return (await historyPoint(text)) !== null; },
        async historyMarker(text) {
            const point = await historyPoint(text);
            return point ? Math.round(point.y) : null;
        },
        async clickHistory(text) {
            await leaveIndex();
            await clickPoint(await wait(`browser History disclosure ${text}`, () => historyPoint(text, true)));
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
    const clickPoint = ({ x, y }, button = 0) => raw(`\x1b[<${button};${x};${y}M\x1b[<${button};${x};${y}m`);
    async function click(text) {
        const { x, y } = await wait(`PTY control ${text}`, async () => {
            const lines = (await capture()).split("\n");
            const y = lines.findIndex(line => line.includes(text));
            return y < 0 ? null : { x: lines[y].indexOf(text) + 1, y: y + 1 };
        });
        await clickPoint({ x, y });
    }
    // Two fixed top bars, then the right History Index header. The one-cell splitter
    // is at its left; bound history searches before it and above the composer.
    const indexStart = lines => (lines[2] ?? "").indexOf("History Index", 65);
    async function indexPoint(text, button = false) {
        const lines = (await capture()).split("\n");
        const start = indexStart(lines);
        if (start < 0) return null;
        for (let y = 3; y < lines.length; y++) {
            const row = lines[y].slice(start);
            if (!/^\s*[┌├└]?● /.test(row)) continue;
            const label = row.indexOf(text);
            if (label < 0) continue;
            const x = button ? row.indexOf("[Enter]", label + text.length) : label;
            if (x >= 0) return { x: start + x + (button ? 2 : 1), y: y + 1 };
        }
        return null;
    }
    async function leaveIndex() {
        await raw("\x1b[<35;70;1M");
        await wait("PTY Index hover dismissed", async () => !(await capture()).includes("Index: "));
    }
    async function historyPoint(text) {
        const lines = (await capture()).split("\n");
        const start = indexStart(lines);
        if (start < 0) return null;
        const history = lines.map(line => line.slice(0, start - 1));
        const composerSeparator = history.findLastIndex(line => line.split("-").length > 60);
        const y = history.findIndex((line, index) => index >= 2 && index < composerSeparator && line.includes(text));
        return y < 0 ? null : { x: history[y].indexOf(text) + 1, y: y + 1 };
    }
    const tabLabels = line => Array.from(line.matchAll(/\[([^\]]+)]/g), match => ({
        label: match[1].replace(/^[\u2800-\u28ff]\s*/, ""), x: match.index + 2,
    })).filter(tab => tab.label !== "+");
    await wait("TUI new Session", async () => (await capture()).includes("[+]"));
    return {
        leaveIndex,
        async waitForEmptyComposer() {
            await wait("PTY composer cleared the accepted draft", async () => {
                const screen = await capture();
                return !screen.includes("Submitting…") && /^>\s*$/m.test(screen);
            });
        },
        type: text => keys("-l", text),
        enter: () => keys("Enter"),
        async showIndex(_index, label) {
            if (indexStart((await capture()).split("\n")) < 0) await click("[History]");
            await wait(`PTY right History Index: ${label}`, () => indexPoint(label));
        },
        async enterChild(label) {
            await leaveIndex();
            await clickPoint(await wait(`PTY Enter button for ${label}`, () => indexPoint(label, true)));
        },
        async menu(text) {
            await leaveIndex();
            const point = await wait(`PTY Index row ${text}`, () => indexPoint(text));
            await clickPoint(point, 2);
            await delay(100);
            const menu = await capture();
            if (menu.includes("Revert to here") && !menu.includes("Go to")) {
                await keys("Escape");
                await clickPoint(point);
                await raw("\x1b[21;2~");
            }
        },
        click,
        async indexMenu(index) {
            const screen = await capture();
            return screen.includes("Go to") && new RegExp(`\\bIndex: ${index}\\b`).test(screen);
        },
        async clickHistory(text) {
            await clickPoint(await wait(`PTY History disclosure ${text}`, () => historyPoint(text)));
        },
        async followingLatest() { return !(await capture()).includes("[↓]"); },
        async revertAndEdit() {
            await wait("PTY Revert and edit menu action", async () => /\[Revert and edit\s*]/.test(await capture()));
            // Last enabled item in the child history menu. Keyboard avoids overlap with the composer.
            await keys("End");
            await keys("Enter");
        },
        async visible(text) { return (await capture()).includes(text); },
        async tabs() {
            return tabLabels((await capture()).split("\n")[0]);
        },
        async tab(label, menu = false) {
            const tab = await wait(`PTY Agent tab ${label}`, async () =>
                tabLabels((await capture()).split("\n")[0]).find(tab => tab.label === label));
            await clickPoint({ x: tab.x, y: 1 }, menu ? 2 : 0);
        },
        async inHistory(text) { return (await historyPoint(text)) !== null; },
        async historyMarker(text) { return (await historyPoint(text))?.y ?? null; },
        async historyMenu(text) {
            await clickPoint(await wait(`PTY History control ${text}`, () => historyPoint(text)), 2);
        },
        async close() { await tmux("kill-server"); tmux = undefined; },
    };
}

try {
    for (const path of [home, nav, work, env.XDG_RUNTIME_DIR]) await mkdir(path, { recursive: true, mode: 0o700 });
    await symlink(cli, join(nav, "kodex"));
    backend = spawnOwned(fixture, [], { cwd: work, env, stdio: ["pipe", "pipe", "pipe"] });
    backend.child.stdin.on("error", () => {});
    backend.child.stdout.on("data", bytes => { logs = (logs + bytes).slice(-8192); });
    backend.child.stderr.on("data", bytes => { backendErrors = (backendErrors + bytes).slice(-8192); });
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
    assert.equal((await command("session", "list")).sessions.length, 1);
    const rootTab = (await wait("one root Agent tab", async () => {
        const tabs = await ui.tabs();
        return tabs.length === 1 ? tabs[0] : null;
    })).label;
    const aTab = "Worker-a", cTab = "Worker-c";
    const expectTabs = (labels, selected) => wait(`tabs ${labels.join(", ")}; selected ${selected}`, async () => {
        const tabs = await ui.tabs();
        if (JSON.stringify(tabs.map(tab => tab.label)) !== JSON.stringify(labels)) return null;
        if (site && tabs.filter(tab => tab.selected).map(tab => tab.label).join() !== selected) return null;
        return tabs;
    });
    const again = async (labels, selected, id) => {
        const tabs = await expectTabs(labels, selected);
        if (site) assert.equal(tabs.find(tab => tab.label === cTab)?.id, id);
        return tabs;
    };
    await expectTabs([rootTab], rootTab);
    db = new DatabaseSync(join(kodexHome, "kodex.db"), { readOnly: true });
    const onlyC = () => db.prepare("SELECT agent_id FROM agent_commits WHERE type='user_message' AND payload_json LIKE ?")
        .all("%NAV_ONLY_C%").map(row => row.agent_id);
    const completeRow = () => db.prepare("SELECT count(*) AS n FROM agent_commits WHERE agent_id=? AND type='event_subagent_completed' AND json_extract(payload_json, '$.child_agent_id')=?")
        .get(a.agent_id, c.agent_id).n;
    const completionIndex = () => db.prepare('SELECT "index" AS n FROM agent_commits WHERE agent_id=? AND type=\'event_subagent_completed\' AND json_extract(payload_json, \'$.child_agent_id\')=?')
        .get(a.agent_id, c.agent_id)?.n;
    const creation = child => db.prepare('SELECT "index" AS n FROM agent_commits WHERE agent_id=? AND type=\'event_subagent_created\' AND json_extract(payload_json, \'$.child_agent_id\')=?')
        .get(child.parent_agent_id, child.agent_id)?.n;
    const userIndex = (id, marker) => db.prepare(`SELECT "index" AS n FROM agent_commits
        WHERE agent_id=? AND type='user_message' AND payload_json LIKE ? ORDER BY "index" LIMIT 1`)
        .get(id, `%${marker}%`)?.n;
    const createdA = "Subagent Worker-a created";
    const createdC = "Subagent Worker-c created";
    const completedC = `Subagent #${c.agent_id} completed`;
    const locateHistory = "Go to";
    const idle = id => wait(`Agent ${id} settled`, async () => !(await command("agent", "status", "--agent", id)).running);
    async function goTo(label, index, anchor) {
        assert.ok(Number.isInteger(index), `Missing fixture index for ${label}`);
        await ui.menu(label, index);
        await wait(`Go to menu for index ${index}`, () => ui.indexMenu(index));
        await ui.click(locateHistory);
        await wait(`index ${index} menu closes`, async () => !await ui.visible(locateHistory));
        await wait(`Go to locates ${anchor} in main History`, () => ui.inHistory(anchor));
    }
    async function tailHides(anchor) {
        if (!await ui.followingLatest()) await ui.click("[↓]");
        await wait(`${anchor} is offscreen in main History at latest`, async () =>
            await ui.followingLatest() && !await ui.inHistory(anchor));
    }
    async function separateAnchorFromTail(anchor, tag) {
        const message = [tag, ...Array.from({ length: 52 }, (_, i) => `Navigation spacer ${i + 1}`)].join("\n");
        await command("agent", "submit", "--agent", a.agent_id, "--message", message);
        await wait(`A persisted ${tag}`, () => Number.isInteger(userIndex(a.agent_id, tag)));
        await idle(a.agent_id);
        await tailHides(anchor);
    }
    const rootCreation = creation(a);
    assert.ok(Number.isInteger(rootCreation));
    await idle(rootId);
    await ui.showIndex(rootCreation, aTab);
    await goTo(aTab, rootCreation, createdA);
    assert.ok(Number.isInteger(userIndex(rootId, "NAV_FIXTURE_ROOT")), "Go to must not alter the root prompt");
    await expectTabs([rootTab], rootTab);
    assert.ok(!await ui.visible("Return to creation"));
    async function enter(label, child, marker) {
        const index = creation(child);
        assert.ok(Number.isInteger(index));
        await ui.enterChild(label, index);
        await show(marker);
    }
    await enter(aTab, a, "NAV_A_READY");
    await expectTabs([rootTab, aTab], aTab);
    await separateAnchorFromTail(createdC, "NAV_PAD_AFTER_CREATION");
    await ui.type("NAV_DRAFT_A");
    await enter(cTab, c, "NAV_C_READY");
    const cIdentity = (await expectTabs([rootTab, aTab, cTab], cTab))[2].id;
    assert.ok(!await ui.visible("NAV_DRAFT_A"));
    await ui.type("NAV_ONLY_C");
    await ui.enter();
    await wait("exactly C received the message", () => onlyC().length > 0);
    assert.deepEqual(onlyC(), [c.agent_id]);
    await ui.leaveIndex();
    // The database write can precede the reply that unlocks the editor.
    await ui.waitForEmptyComposer?.();
    await ui.type("NAV_DRAFT_C");
    await show("NAV_DRAFT_C");
    await ui.click("Return to creation");
    await show("NAV_DRAFT_A");
    await wait("parent creation appears in History after return", () => ui.inHistory(createdC));
    await again([rootTab, aTab, cTab], aTab, cIdentity);
    assert.ok(!await ui.visible("NAV_DRAFT_C"));
    assert.ok(!await ui.inHistory("NAV_FIXTURE_C"));
    await ui.clickHistory(createdC);
    await wait("the delegated prompt is visible below the spawn heading", async () => {
        const heading = await ui.historyMarker(createdC);
        const prompt = await ui.historyMarker("NAV_FIXTURE_C");
        return heading !== null && prompt !== null && prompt > heading;
    });
    await ui.clickHistory(createdC);
    await wait("the delegated prompt is hidden again", async () => !await ui.inHistory("NAV_FIXTURE_C"));
    assert.equal((await command("agent", "status", "--agent", c.agent_id)).running, true);
    await ui.tab(cTab);
    await show("NAV_DRAFT_C");
    await again([rootTab, aTab, cTab], cTab, cIdentity);
    await ui.tab(aTab);
    await show("NAV_DRAFT_A");
    await enter(cTab, c, "NAV_DRAFT_C");
    await again([rootTab, aTab, cTab], cTab, cIdentity);
    await wait("C's submitted message is in its main History after re-entry", () => ui.inHistory("NAV_ONLY_C"));
    assert.equal(completeRow(), 0);
    await show("Return to creation");
    assert.ok(!await ui.visible("Return to completion"));
    console.log(`${mode}: creation path, scroll-only Go to, running C and draft via top bar/history verified`);

    await ui.tab(aTab, true);
    await ui.click("Close Agent tab");
    await again([rootTab, cTab], cTab, cIdentity);
    await show("NAV_DRAFT_C");
    await ui.type("_AFTER_CLOSE");
    await show("NAV_DRAFT_C_AFTER_CLOSE");
    assert.equal((await command("agent", "status", "--agent", c.agent_id)).running, true);
    assert.equal(completeRow(), 0);
    assert.ok(!await ui.visible("Return to completion"));
    await ui.click("Return to creation");
    await again([rootTab, cTab, aTab], aTab, cIdentity);
    await wait("explicit return reopens direct parent at C's creation", () => ui.inHistory(createdC));
    assert.ok(!await ui.visible("NAV_DRAFT_C_AFTER_CLOSE"));
    console.log(`${mode}: closed A only; C draft editable; creation reopens A without a completion anchor`);

    await ui.tab(cTab);
    await show("NAV_DRAFT_C_AFTER_CLOSE");
    await command("agent", "stop", "--agent", c.agent_id);
    const completion = await command("agent", "complete", "--agent", c.agent_id);
    assert.ok(completion.completed);
    await wait("parent consumed one completion", () => completeRow() === 1);
    await idle(a.agent_id);
    const completedIndex = completionIndex();
    assert.ok(Number.isInteger(completedIndex) && completedIndex > creation(c));
    await ui.tab(aTab);
    await again([rootTab, cTab, aTab], aTab, cIdentity);
    await separateAnchorFromTail(completedC, "NAV_PAD_AFTER_COMPLETION");
    await ui.tab(cTab);
    await show("NAV_DRAFT_C_AFTER_CLOSE");
    await ui.click("Return to completion");
    // The pointer now lies over the replacement Index. Its hover can obscure
    // the main History in both PTY captures and the browser accessibility tree.
    await ui.leaveIndex();
    await again([rootTab, cTab, aTab], aTab, cIdentity);
    await wait("parent completion appears in History after return", () => ui.inHistory(completedC));
    await tailHides(completedC);
    await goTo(completedC, completedIndex, completedC);
    assert.equal(completionIndex(), completedIndex, "Go to must not rewrite the completion record");
    assert.equal(completeRow(), 1);
    await again([rootTab, cTab, aTab], aTab, cIdentity);
    console.log(`${mode}: completion return selects A, and Go to locates the exact C notification without switching tabs`);

    const aPrompt = "NAV_FIXTURE_A", aPromptIndex = userIndex(a.agent_id, aPrompt);
    assert.ok(Number.isInteger(aPromptIndex) && aPromptIndex < creation(c));
    await goTo(aPrompt, aPromptIndex, aPrompt);
    if (site) await ui.menu(aPrompt, aPromptIndex);
    else await ui.historyMenu(aPrompt);
    await ui.revertAndEdit(aPromptIndex);
    await wait("reverting A removed the previously valid creation and completion", () =>
        completeRow() === 0 && creation(c) === undefined && userIndex(a.agent_id, aPrompt) === undefined);
    const emptyHistory = "No conversation history items";
    const aPosition = await wait("A has rendered the reverted history", async () =>
        !await ui.inHistory(createdC) && !await ui.inHistory(completedC) && await ui.historyMarker(emptyHistory));
    await ui.tab(cTab);
    await show("NAV_DRAFT_C_AFTER_CLOSE");
    await show("Return to creation");
    assert.ok(!await ui.visible("Return to completion"));
    await ui.click("Return to creation");
    await again([rootTab, cTab, aTab], aTab, cIdentity);
    await wait("A's empty History viewport after return", () => ui.historyMarker(emptyHistory));
    // Parent selection publishes before the anchor lookup finishes; also check the subsequent frames.
    for (let i = 0; i < 5; i++) {
        assert.equal(await ui.historyMarker(emptyHistory), aPosition);
        assert.ok(!await ui.inHistory(createdC));
        assert.ok(!await ui.inHistory(completedC));
        await delay(125, undefined, { signal: abort.signal });
    }
    console.log(`${mode}: real history revert invalidated creation/completion; C still returns to A without a fake anchor or viewport jump`);

    await ui.click("Return to creation");
    await show("NAV_ROOT_READY");
    assert.ok(!await ui.visible("NAV_DRAFT_A"));
    await expectTabs([rootTab, cTab, aTab], rootTab);
    assert.equal((await command("session", "list")).sessions.length, 1);
    assert.equal(db.prepare("SELECT count(*) AS n FROM agent_commits WHERE type='user_message' AND payload_json LIKE ?")
        .get("%NAV_DRAFT_%").n, 0);
    await ui.close();
    console.log(JSON.stringify({ mode, session: a.session_id, path: [rootId, a.agent_id, c.agent_id],
        oneSession: true, independentAgentTabs: true, draftRetained: true, parentReopened: true,
        returnedWhileRunning: true, completedAtParent: true, anchorsInvalidated: true }));
} catch (error) {
    if (process.env.KODEX_NAVIGATION_DIAGNOSTIC === "1") {
        console.error(diagnostic);
        if (backendErrors) console.error("Isolated Backend stderr:", backendErrors);
    }
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

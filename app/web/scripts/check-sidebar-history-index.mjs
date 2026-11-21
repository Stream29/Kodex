import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, readlink, realpath, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { DatabaseSync } from "node:sqlite";
import { cleanupSteps, privateEnvironment, spawnOwned, stopOwned } from "./local-entry-tools.mjs";

const [mode, fixturePath, targetPath] = process.argv.slice(2);
assert.ok(process.platform === "linux" && process.argv.length === 5 && ["browser", "tui"].includes(mode),
    "Usage (Node 22+): node check-sidebar-history-index.mjs browser|tui FIXTURE WEBSITE|NATIVE_CLI");
if (mode === "browser") assert.ok(process.env.CHROME_BIN && globalThis.WebSocket, "Set CHROME_BIN and use Node 22+.");
const [fixture, target, chrome] = await Promise.all([
    realpath(fixturePath), realpath(targetPath), mode === "browser" ? realpath(process.env.CHROME_BIN) : null,
]);
const root = await mkdtemp("/tmp/kodex-sidebar-index-");
const home = join(root, "os-home"), runtime = join(root, "runtime"), tmp = join(root, "tmp"), cwd = join(root, "project");
const env = {
    ...privateEnvironment({ home, bin: root, runtime, temporary: root, capture: join(root, "unused") }),
    TZ: "UTC", TERM: "xterm-256color", KODEX_CHECK_SIDEBAR_HISTORY_INDEX: "1",
    ...(mode === "browser" ? { KODEX_BROWSER_FIXTURE_WEBSITE: target } : {}),
};
const stopped = new AbortController();
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => stopped.abort(new Error("Interrupted")));
let backend, frontend, socket, closeTui, snapshot, stage, failure, server = "";
const checks = [];

async function wait(label, read, ms = 20_000) {
    stage = label;
    const end = Date.now() + ms;
    do {
        stopped.signal.throwIfAborted();
        assert.equal(backend?.result ?? null, null, "Owned backend exited");
        assert.equal(frontend?.result ?? null, null, "Owned browser exited");
        const result = await read();
        if (result) return result;
        await delay(50, undefined, { signal: stopped.signal });
    } while (Date.now() < end);
    throw new Error(`Timed out: ${label}`);
}

function storage(home) {
    const read = block => {
        const db = new DatabaseSync(join(home, "kodex.db"), { readOnly: true });
        try { return block(db); } finally { db.close(); }
    };
    const agents = () => read(db => db.prepare(
        "SELECT agent_id AS id, session_id AS session, parent_agent_id AS parent FROM agents ORDER BY agent_id",
    ).all());
    const rows = id => read(db => db.prepare(
        'SELECT "index" AS idx, type, payload_json AS payload FROM agent_commits WHERE agent_id = ? ORDER BY "index"',
    ).all(id));
    const initial = agents();
    assert.equal(initial.length, 2, "Dedicated fixture must own exactly one root and one child");
    const source = initial.find(agent => agent.parent === null);
    const child = initial.find(agent => agent.parent === source.id);
    assert.equal(child.session, source.session);
    const marker = (id, value) => rows(id).find(row => row.payload.includes(value));
    const index = (id, value) => {
        const row = marker(id, value);
        assert.ok(row, `Fixture missing ${value}`);
        return row.idx;
    };
    return { agents, rows, source, child, marker, index };
}

function exactFixtureTimestamp(text) {
    // Native uses the device timezone, which need not honor the TZ passed to the PTY.
    return [...text.matchAll(/Timestamp: (\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) UTC([+-]\d{2}:\d{2})/g)]
        .some(([, date, time, offset]) => Date.parse(`${date}T${time}${offset}`) === Date.parse("2026-10-03T01:02:03Z"));
}

async function scenario(ui, db) {
    const { source, child, index, marker } = db;
    const sourceRow = text => ({ index: index(source.id, text), text });
    const childRow = text => ({ index: index(child.id, text), text });
    const scroll = sourceRow("RootScrollTarget");
    const long = sourceRow("PreviewStart");
    const plan = sourceRow("PreviewPlanStep");
    const ask = { index: index(source.id, "FixtureQuestion"), text: "Enter the fixture" };
    const creation = { index: index(source.id, "IdxChild"), text: "IdxChild · Spawned" };
    const rootFork = sourceRow("RootForkPoint"), childFork = childRow("ChildForkPoint");
    const edit = sourceRow("RootEditDraft"), revert = sourceRow("RootRevertPoint");
    await ui.openSource();
    await wait("source loaded in main History", () => ui.inHistory(sourceRow("RootTail")));
    await wait("root History Index", () => ui.row(scroll));

    await ui.prepareAdjacentHover?.(plan);
    await ui.hover(long);
    await wait("Agent message hover metadata", async () =>
        await ui.preview("Agent message") && await ui.preview(`Index: ${long.index}`) &&
        await ui.preview("Author: FixtureAuthor") && await ui.preview("Recipient: FixtureRecipient"));
    await ui.crossIntoPreview(long);
    await ui.selectPreviewText?.();
    await ui.previewTail("PreviewEnd");
    await ui.hover(plan);
    await wait("typed Plan hover", async () =>
        await ui.preview("Plan update") && await ui.preview(`Index: ${plan.index}`) &&
        await ui.preview("PreviewPlanExplanation") && await ui.preview("PreviewPlanStep"));
    await ui.leave();
    await ui.hover(ask);
    await wait("redacted RequestUserInput hover", async () =>
        await ui.preview(`Index: ${ask.index}`) && await ui.preview("FixtureQuestion") && await ui.preview("[hidden]"));
    assert.ok(!await ui.text("HiddenFixtureAnswerNeverRender"), "No raw answer may reach the rendering surface");
    await ui.leave();
    await ui.hover(scroll);
    await wait("hover timestamp", async () =>
        await ui.preview(`Index: ${scroll.index}`) && await ui.timestamp());
    await ui.leave();
    checks.push("Agent message, full text, typed Plan, redacted questions in hover");

    assert.ok(!await ui.inHistory(scroll), "Seed must initially put the Go to target outside main history");
    await ui.primary(scroll);
    assert.ok(!await ui.inHistory(scroll), "Primary click must not navigate");
    await ui.context(scroll);
    await wait("compact menu and formatted timestamp", async () =>
        await ui.text("Go to") && await ui.text(`Index: ${scroll.index}`) &&
        await ui.timestamp());
    assert.ok(!await ui.menuAction("Details") && !await ui.preview("User message"), "No inline preview or Details command");
    assert.ok(await ui.text("Revert and edit"), "A plain user message offers edit");
    await ui.click("Go to");
    await wait("Go to reveals exact entry in main history", () => ui.inHistory(scroll));
    assert.ok(marker(source.id, "RootTail"), "Go to is read only");
    checks.push("inert primary click, compact menu, formatted timestamp and exact Go to");

    async function fork(row, omitted, original, creationFiltered = false) {
        const before = new Set(db.agents().map(agent => agent.id));
        await ui.context(row);
        await ui.click("Fork from here");
        const forked = await wait(`new independent root from ${row.text}`, () => {
            const agents = db.agents();
            const added = agents.filter(agent => !before.has(agent.id));
            if (added.length === 0) return null;
            assert.equal(added.length, 1);
            const [fork] = added;
            assert.equal(fork.parent, null);
            assert.ok(![source.session, child.session].includes(fork.session));
            assert.equal(agents.filter(agent => agent.session === fork.session).length, 1);
            return marker(fork.id, row.text) && fork;
        });
        await wait(`opened the ${row.text} fork`, async () =>
            await ui.inHistory(row) && !await ui.text(omitted));
        assert.ok(!marker(forked.id, omitted) && marker(original.id, omitted));
        if (creationFiltered) assert.ok(!db.rows(forked.id).some(row => row.type === "event_subagent_created"));
        checks.push(`forked ${row.text} into distinct Session ${forked.session} / Agent ${forked.id}`);
    }
    await fork(rootFork, "RootRevertPoint", source, true);
    await ui.sourceTab();
    await wait("returned to original root", () => ui.row(revert));
    await wait("child creation row has one Enter action", async () => await ui.enterActions() === 1);
    await ui.context(creation);
    await wait("child creation context menu", () => ui.text("Go to"));
    assert.ok(!await ui.text("Enter subagent") && !await ui.text("Revert and edit"),
        "Creation rows keep Enter on the row, not as a menu command");
    await ui.escape();
    await wait("creation menu closed", async () => !await ui.text("Go to"));
    await ui.prepareEnterChild?.();
    await ui.hover(creation);
    await wait("creation hover alongside Enter", () => ui.preview(`Index: ${creation.index}`));
    await ui.enterChild(creation);
    // Child and root indices overlap; wait for a child-only marker before targeting the numbered row.
    await wait("child History Index", async () => await ui.text("ChildTail") && await ui.row(childFork));
    await fork(childFork, "ChildTail", child);

    await ui.sourceTab();
    await wait("original root available for edits", () => ui.row(edit));
    await ui.context(edit);
    await ui.click("Revert and edit");
    await wait("composer receives draft and focus", () => ui.focusedDraft("RootEditDraft"));
    assert.ok(!marker(source.id, "RootEditDraft") && !marker(source.id, "RootTail"));
    assert.ok(marker(source.id, "RootAfterRevert"));
    // Composer focus and the storage commit can precede the Index repaint.
    await wait("History Index reflects Revert and edit", async () => await ui.row(revert) && !await ui.row(edit));
    await ui.context(revert);
    await wait("non-user message menu", async () =>
        await ui.menuAction("Go to") && await ui.text(`Index: ${revert.index}`));
    assert.ok(!await ui.text("Revert and edit"));
    await ui.click("Revert to here");
    await wait("Revert confirmation", () => ui.text("Revert history to here?"));
    assert.ok(marker(source.id, "RootAfterRevert"), "Show confirmation before changing history");
    await ui.click("Revert");
    await wait("confirmed revert prunes later history", async () =>
        !await ui.text("Revert history to here?") && !marker(source.id, "RootAfterRevert"));
    assert.ok(marker(source.id, "RootRevertPoint") && marker(child.id, "ChildTail"));
    checks.push("revert and edit refills/focuses composer; confirmed revert keeps the selected boundary");
}

async function browser(home) {
    const launch = join(home, "frontend-launch.private");
    await wait("fixture's private Web launcher", () => server.includes(`PRODUCT_FRONTEND_READY ${launch}`));
    assert.equal((await stat(launch)).mode & 0o777, 0o600);
    const url = await readFile(launch, "utf8");
    assert.ok(/^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/.test(url),
        "Fixture must supply a private loopback launch URL");
    const profile = join(root, "chrome");
    frontend = spawnOwned(chrome, [
        "--headless=new", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update",
        "--no-first-run", "--no-default-browser-check", "--remote-debugging-address=127.0.0.1",
        "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--force-renderer-accessibility",
        "--window-size=1280,920", "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
        "about:blank",
    ], { cwd, env, stdio: "ignore" });
    const endpoint = await wait("owned Chrome CDP", async () =>
        (await readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""))
            .match(/^(\d+)\n(\/devtools\/browser\/[^\n]+)/));
    socket = new WebSocket(`ws://127.0.0.1:${endpoint[1]}${endpoint[2]}`);
    let id = 0, sessionId, errors = 0, last = [];
    const pending = new Map();
    socket.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        if (message.method === "Runtime.exceptionThrown") errors++;
        pending.get(message.id)?.(message);
    });
    snapshot = () => last.filter(line =>
        /Sessions|cwd|sidebar|Collapse|Refresh|working directory|kodex-product-browser|IdxSource|Index|Fixture|Go to|Revert|Fork|Root|Child|Preview/i.test(line))
        .slice(0, 60).join("\n");
    await wait("CDP WebSocket open", () => socket.readyState === WebSocket.OPEN);
    function send(method, params = {}) {
        return new Promise((resolve, reject) => {
            const key = ++id;
            const timer = setTimeout(() => { pending.delete(key); reject(new Error(`CDP timeout: ${method}`)); }, 10_000);
            pending.set(key, message => {
                clearTimeout(timer);
                pending.delete(key);
                if (message.error) reject(new Error(`CDP failed: ${method}`)); else resolve(message.result);
            });
            socket.send(JSON.stringify({ id: key, method, params, ...(sessionId ? { sessionId } : {}) }));
        });
    }
    try {
        const { targetId } = await send("Target.createTarget", { url: "about:blank" });
        sessionId = (await send("Target.attachToTarget", { targetId, flatten: true })).sessionId;
        for (const domain of ["Page", "Runtime", "Accessibility"]) await send(`${domain}.enable`);
        const nodes = async () => {
            const result = (await send("Accessibility.getFullAXTree")).nodes.filter(node => !node.ignored);
            last = result.filter(node => node.name?.value).map(node => {
                const state = node.properties?.filter(property => ["selected", "pressed", "expanded"].includes(property.name))
                    .map(property => `${property.name}=${property.value.value}`).join(" ");
                return `${node.role?.value}: ${node.name.value}${state ? ` (${state})` : ""}`;
            });
            return result;
        };
        const name = node => node.name?.value ?? "";
        const isButton = label => node => node.role?.value === "button" && name(node) === label;
        // AX descriptions can be joined with the row label by the accessibility mirror.
        const find = async test => (await nodes()).find(node => node.backendDOMNodeId && test(node));
        const text = async value => (await nodes()).some(node => name(node).includes(value));
        const bounds = async node => {
            if (!node) return null;
            const model = (await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }).catch(() => null))?.model;
            if (!model?.width || !model.height) return null;
            const box = model.content;
            return { left: box[0], top: box[1], right: box[4], bottom: box[5] };
        };
        const point = async node => {
            const box = await bounds(node);
            return box && { x: (box.left + box.right) / 2, y: (box.top + box.bottom) / 2 };
        };
        const row = async ({ index }) => {
            const node = await find(node => new RegExp(`\\bHistory index ${index}\\b`).test(name(node)));
            const header = await bounds(await find(isButton("Collapse right sidebar")));
            const main = await bounds(await find(node => name(node) === "Agent page history"));
            const center = await point(node);
            return header && main && center && center.x >= main.right && center.x < header.right &&
                center.y >= header.bottom && center.y < main.bottom ? node : null;
        };
        async function mouse(type, at, button = "none", buttons = 0) {
            await send("Input.dispatchMouseEvent", { type, ...at, button, buttons, clickCount: button === "none" ? 0 : 1 });
        }
        async function press(at, button = "left") {
            await mouse("mousePressed", at, button, button === "right" ? 2 : 1);
            await mouse("mouseReleased", at, button);
        }
        const clickWhere = async (label, matches) => press(await wait(label, async () => point(await find(matches))));
        const click = label => clickWhere(label, isButton(label));
        async function clickToolWindow(label) {
            await click(label);
            // A tool-window tooltip can temporarily mask the application's AX tree.
            await mouse("mouseMoved", { x: 500, y: 12 });
        }
        const rowPoint = item => wait(`browser sidebar row ${item.index}`, async () => point(await row(item)));
        const rowBounds = item => wait(`measured browser sidebar row ${item.index}`, async () => bounds(await row(item)));
        const previewBounds = item => wait("measured hover metadata", async () =>
            bounds(await find(node => name(node) === `Index: ${item.index}`)));
        // While a popup is present, the browser mirror exposes the popup rather than the rows behind it.
        let scrollPoint, hoveredRow, adjacentRow, enterChildPoint;
        const leave = async () => {
            await mouse("mouseMoved", { x: 4, y: 4 });
            await wait("hover leaves the document surface", async () =>
                !(await nodes()).some(node => /^(Index: \d+)$/.test(name(node))));
        };
        await send("Page.navigate", { url });
        await wait("Backend: Connected", () => text("Backend: Connected"));
        await scenario({
            text, row, click, leave,
            timestamp: async () => exactFixtureTimestamp((await nodes()).map(name).join("\n")),
            menuAction: label => find(isButton(label)).then(Boolean),
            enterActions: async () => (await nodes()).filter(node =>
                node.role?.value === "button" && /^Enter subagent$/.test(name(node))).length,
            preview: text,
            prepareAdjacentHover: async item => { adjacentRow = { index: item.index, bounds: await rowBounds(item) }; },
            prepareEnterChild: async () => {
                enterChildPoint = await wait("measured row-tail Enter", async () =>
                    point(await find(node => node.role?.value === "button" && name(node) === "Enter subagent")));
            },
            crossIntoPreview: async item => {
                const { index, bounds: rowRect, point: from } = hoveredRow;
                assert.equal(index, item.index);
                const before = await previewBounds(item);
                assert.ok(before.left >= 0 && before.right < rowRect.left,
                    "The right sidebar preview must start to the left of its row and stay in the viewport");
                await mouse("mouseMoved", { x: from.x + 24, y: from.y });
                await delay(60);
                assert.deepEqual(await previewBounds(item), before, "In-row motion cannot move the preview");
                await mouse("mouseMoved", { x: before.right - 2, y: from.y });
                await delay(230);
                assert.deepEqual(await previewBounds(item), before, "Crossing to the preview must outlast close grace");
            },
            selectPreviewText: async () => {
                const body = await wait("measured selectable preview", async () =>
                    bounds(await find(node => name(node).startsWith("Author: FixtureAuthor"))));
                const start = { x: body.left + 2, y: body.top + 10 };
                const end = { x: Math.min(body.left + 125, body.right - 2), y: start.y };
                const clip = { x: Math.ceil(start.x + 5), y: Math.ceil(body.top + 2),
                    width: Math.floor(end.x - start.x - 10), height: 15, scale: 1 };
                assert.ok(clip.width > 30, "The selection strip must cover the preview's first line");
                const capture = async () => (await send("Page.captureScreenshot", { clip })).data;
                await mouse("mouseMoved", start);
                await press(start); // Put pointer and focus in the preview before recording the baseline.
                await delay(120);
                const original = await capture();
                await mouse("mousePressed", start, "left", 1);
                for (const portion of [0.3, 0.65, 1]) {
                    await mouse("mouseMoved", { x: start.x + portion * (end.x - start.x), y: start.y }, "none", 1);
                    await delay(35);
                }
                await mouse("mouseReleased", end, "left");
                await wait("drag selection paints the preview's first text line", async () => await capture() !== original);
                await delay(550); // End the double-click window before clearing the range.
                const clear = { x: start.x, y: start.y + 24 };
                await mouse("mouseMoved", clear);
                await press(clear);
                await wait("clearing selection restores the same preview strip", async () => await capture() === original);
                scrollPoint = { x: start.x + 16, y: clear.y };
            },
            previewTail: async value => {
                await wait(`full ${value} in hover semantics`, () => text(value));
                const capture = async () => (await send("Page.captureScreenshot")).data;
                await mouse("mouseMoved", scrollPoint);
                const before = await capture();
                await send("Input.dispatchMouseEvent", { type: "mouseWheel", ...scrollPoint, deltaX: 0, deltaY: 380 });
                await wait("browser wheel repaints the hover body", async () => await capture() !== before);
            },
            openSource: async () => {
                await wait("Sessions tool-window rail", async () => bounds(await find(isButton("Sessions"))));
                if (!await find(isButton("Collapse Sessions"))) await clickToolWindow("Sessions");
                await wait("left grouped Sessions", async () =>
                    await find(isButton("Collapse Sessions")) && await find(isButton("Refresh sessions")));
                await clickWhere("source row in grouped Sessions", isButton("IdxSource"));
                const historyTailPoint = await wait("source's static RootTail text in main History", async () => {
                    const tree = await nodes();
                    const viewport = await bounds(tree.find(node => name(node) === "Agent page history"));
                    if (!viewport) return null;
                    for (const node of tree.filter(node => node.role?.value === "StaticText" && name(node) === "RootTail")) {
                        const at = await point(node);
                        if (at && at.x >= viewport.left && at.x < viewport.right &&
                            at.y >= viewport.top && at.y < viewport.bottom) return at;
                    }
                    return null;
                });
                const sourceRemains = tree =>
                    !tree.some(isButton("Collapse Sessions")) && !tree.some(isButton("Refresh sessions")) &&
                    tree.some(node => node.role?.value === "tab" && name(node).includes("IdxSource")) &&
                    tree.some(node => node.role?.value === "StaticText" && name(node) === "RootTail");
                await clickToolWindow("Collapse Sessions");
                const afterCollapse = await wait("left Sessions closed or its rail tooltip obscures the AX tree", async () => {
                    const tree = await nodes();
                    if (sourceRemains(tree)) return "closed";
                    const leaves = tree.filter(node => node.role?.value === "StaticText" && name(node));
                    if (leaves.length === 1 && name(leaves[0]) === "Sessions" &&
                        !tree.some(node => node.role?.value === "button" || node.role?.value === "tab")) return "tooltip";
                    return null;
                });
                if (afterCollapse === "tooltip") {
                    // Collapse restores focus to the rail; click the measured, inert text to move focus off it.
                    await press(historyTailPoint);
                }
                await wait("left Sessions closed without closing the source tab or losing RootTail", async () =>
                    sourceRemains(await nodes()));
                if (!await find(isButton("Collapse right sidebar"))) await clickToolWindow("History Index");
                await wait("right History Index open", () => find(isButton("Collapse right sidebar")));
            },
            sourceTab: () => clickWhere("original root tab", node =>
                node.role?.value === "tab" && name(node).includes("IdxSource") && name(node).includes("Session tab")),
            hover: async item => {
                const rect = adjacentRow?.index === item.index ? adjacentRow.bounds : await rowBounds(item);
                if (adjacentRow?.index === item.index) adjacentRow = null;
                const point = { x: (rect.left + rect.right) / 2, y: (rect.top + rect.bottom) / 2 };
                hoveredRow = { index: item.index, bounds: rect, point };
                await mouse("mouseMoved", point);
            },
            primary: async item => press(await rowPoint(item)),
            context: async item => press(await rowPoint(item), "right"),
            enterChild: () => press(enterChildPoint),
            escape: async () => {
                for (const type of ["rawKeyDown", "keyUp"]) await send("Input.dispatchKeyEvent", {
                    type, key: "Escape", code: "Escape", windowsVirtualKeyCode: 27,
                });
            },
            inHistory: async ({ index }) => {
                const item = await point(await find(node => name(node) === `History entry ${index}`));
                const region = await find(node => name(node) === "Agent page history");
                if (!item || !region) return false;
                const model = (await send("DOM.getBoxModel", { backendNodeId: region.backendDOMNodeId }).catch(() => null))?.model;
                return !!model && item.x >= model.content[0] && item.x <= model.content[4] &&
                    item.y >= model.content[1] && item.y <= model.content[5];
            },
            focusedDraft: async value => {
                const { result } = await send("Runtime.evaluate", { returnByValue: true, expression: `(() => {
                    function visit(root) {
                        for (const e of root.querySelectorAll('*')) {
                            if (e.tagName === 'TEXTAREA' && e.getAttribute('aria-label') === 'Message' &&
                                e.value === ${JSON.stringify(value)} && e.getRootNode().activeElement === e) return true;
                            if (e.shadowRoot && visit(e.shadowRoot)) return true;
                        }
                        return false;
                    }
                    return visit(document);
                })()` });
                return result.value;
            },
        }, storage(home));
        assert.equal(errors, 0, "Browser runtime exceptions");
    } finally {
        sessionId = undefined;
        if (socket.readyState === WebSocket.OPEN) await send("Browser.close").catch(() => {});
        socket.close();
    }
}

async function tui(home, record) {
    const { port } = JSON.parse(await readFile(record, "utf8"));
    assert.ok(Number.isInteger(port) && port > 0 && port <= 65535);
    const mux = join(root, "mux.sock"), launcher = join(root, "launch-tui"), pidFile = join(root, "tui.pid");
    const run = promisify(execFile);
    const ttyEnv = { ...env, PRIVATE_CLI: target, PRIVATE_HOME: home, PRIVATE_PORT: String(port), PRIVATE_PID: pidFile };
    const tmux = async (...args) => (await run("/usr/bin/tmux", ["-S", mux, "-f", "/dev/null", ...args],
        { cwd, env: ttyEnv, timeout: 5_000 })).stdout;
    const pane = "sidebar-index:0.0";
    await writeFile(launcher, [
        'set -eu; umask 077; printf "%s" "$$" > "$PRIVATE_PID"',
        'exec "$PRIVATE_CLI" tui "--backend-url=http://127.0.0.1:$PRIVATE_PORT" "--backend-home=$PRIVATE_HOME"',
    ].join("\n") + "\n", { mode: 0o600 });
    closeTui = async () => {
        await tmux("kill-server").catch(() => {});
        const pid = Number(await readFile(pidFile, "utf8").catch(() => ""));
        if (!Number.isSafeInteger(pid) || pid <= 1) return;
        const ours = async () =>
            await readlink(`/proc/${pid}/exe`).catch(() => "") === target &&
            (await readFile(`/proc/${pid}/cmdline`, "utf8").catch(() => "")).includes(`--backend-home=${home}\0`);
        if (await ours()) process.kill(pid, "SIGTERM");
        for (let i = 0; i < 50 && await ours(); i++) await delay(25);
        assert.ok(!await ours(), "Private CLI is still running after tmux shutdown");
    };
    const columns = 116, rows = 32, sidebarColumns = Math.floor(columns / 4);
    // Zero-based content start; the splitter is the cell immediately before it.
    const indexStart = columns - sidebarColumns + 1;
    await tmux("new-session", "-d", "-s", "sidebar-index", "-x", String(columns), "-y", String(rows), `exec /bin/sh ${launcher}`);
    let last = "";
    snapshot = () => last;
    async function capture() { last = await tmux("capture-pane", "-p", "-t", pane); return last; }
    async function position(value, include = () => true) {
        const lines = (await capture()).split("\n");
        for (const [y, line] of lines.entries()) {
            for (let x = line.indexOf(value); x >= 0; x = line.indexOf(value, x + value.length)) {
                if (include(line, x, y)) return { x: x + 1, y: y + 1 };
            }
        }
        return null;
    }
    const keys = (...args) => tmux("send-keys", "-t", pane, ...args);
    const raw = str => keys("-H", ...Array.from(Buffer.from(str), b => b.toString(16).padStart(2, "0")));
    const mouse = (code, { x, y }, event = "M") => raw(`\x1b[<${code};${x};${y}${event}`);
    async function press(point, code = 0) { await mouse(code, point); await mouse(code, point, "m"); }
    const clickAt = (value, filter) => wait(`TUI visible ${value}`, () => position(value, filter)).then(point => press(point));
    const row = ({ text }) => position(text, (line, x, y) =>
        y >= 3 && x >= indexStart && /^ *[┌├└]?● /.test(line.slice(indexStart)));
    const rowPoint = item => wait(`TUI sidebar ${item.text}`, () => row(item));
    const text = value => position(value);
    const preview = value => position(value, (_, x, y) => y >= 1 && x + value.length <= indexStart - 1);
    const indexHeader = () => position("History Index", (_, x, y) => y === 2 && x === indexStart);
    let adjacentRow, enterChildPoint, longPreview;
    const leave = async () => {
        await mouse(35, { x: 1, y: 1 });
        await wait("TUI hover dismissed", async () => !await preview("Index: "));
    };
    await scenario({
        text, row, preview, leave,
        prepareAdjacentHover: async item => { adjacentRow = { index: item.index, point: await rowPoint(item) }; },
        prepareEnterChild: async () => {
            enterChildPoint = await wait("PTY right Index Enter", () =>
                position("[Enter]", (_, x, y) => x >= indexStart && y >= 3));
        },
        crossIntoPreview: async item => {
            const from = await rowPoint(item);
            const marker = `Index: ${item.index}`;
            const before = await wait("TUI preview outside the sidebar", () => preview(marker));
            longPreview = before;
            const timestamp = (await capture()).match(/Timestamp: \d{4}-\d\d-\d\d \d\d:\d\d:\d\d UTC[+-]\d\d:\d\d/)?.[0] ?? "";
            const width = Math.max("Agent message".length, "Recipient: FixtureRecipient".length, marker.length, timestamp.length);
            assert.equal(before.x + width, indexStart, "Preview ends before the one-cell splitter");
            await mouse(35, { x: from.x + 4, y: from.y });
            await delay(60);
            assert.deepEqual(await preview(marker), before, "In-row motion cannot move the preview");
            await mouse(35, { x: before.x + width - 1, y: from.y });
            await delay(230);
            assert.deepEqual(await preview(marker), before, "Crossing to the preview must outlast close grace");
        },
        timestamp: async () => exactFixtureTimestamp(await capture()),
        menuAction: label => position(label),
        enterActions: async () => (await capture()).split("\n").slice(3).filter(line => line.slice(indexStart).includes("[Enter]")).length,
        click: label => clickAt(label === "Revert" ? "[Revert]" : label),
        openSource: async () => {
            await clickAt("[Sessions]");
            await clickAt("IdxSource", (_, x, y) => x < sidebarColumns && y >= 3);
            await wait("source tab opened", () => position("[IdxSource]", (_, _x, y) => y === 0));
            await clickAt("[Sessions]");
            await wait("left Sessions closed", async () => !await position("Sessions", (_, x, y) => y === 2 && x < sidebarColumns));
            if (!await indexHeader()) await clickAt("[History]");
            await wait("right History Index open", indexHeader);
        },
        sourceTab: () => clickAt("[IdxSource]", (_, _x, y) => y === 0),
        hover: async item => {
            const point = adjacentRow?.index === item.index ? adjacentRow.point : await rowPoint(item);
            if (adjacentRow?.index === item.index) adjacentRow = null;
            await mouse(35, point);
        },
        primary: async item => press(await rowPoint(item)),
        context: async item => press(await rowPoint(item), 2),
        enterChild: () => press(enterChildPoint),
        escape: () => keys("Escape"),
        inHistory: async ({ text }) => {
            const lines = (await capture()).split("\n").map(line => line.slice(0, indexStart - 1));
            const separator = lines.findLastIndex(line => line.split("-").length > 60);
            return lines.slice(2, separator < 0 ? 2 : separator).some(line => line.includes(text));
        },
        previewTail: async value => {
            const bodyLine = text => position(text, (_, x, y) => x === longPreview.x - 1 && y >= longPreview.y - 1);
            assert.ok(!await bodyLine(value), "Long hover must clip the last body line before scrolling");
            const point = await wait("TUI scrollable hover", () => bodyLine("Author: FixtureAuthor"));
            for (let i = 0; i < 20 && !await bodyLine(value); i++) {
                await mouse(65, point);
                await delay(60);
            }
            assert.ok(await bodyLine(value), "Scrolling hover must paint the last body line");
        },
        focusedDraft: async value => {
            if (!await position(value, (_, _x, y) => y >= 28)) return false;
            // A literal keystroke must edit the draft without a preceding click or Tab.
            await keys("-l", "z");
            return wait("TUI key edits the restored draft", async () =>
                await position(`${value}z`, (_, _x, y) => y >= 28) ||
                await position(`z${value}`, (_, _x, y) => y >= 28));
        },
    }, storage(home));
}

try {
    for (const dir of [home, runtime, tmp, cwd]) await mkdir(dir, { mode: 0o700 });
    backend = spawnOwned(fixture, [], { cwd, env, stdio: ["pipe", "pipe", "inherit"] });
    backend.child.stdin.on("error", () => {});
    backend.child.stdout.on("data", bytes => { server = (server + bytes).slice(-8192); });
    const record = await wait("private sidebar backend", () => server.match(/PRODUCT_BROWSER_READY ([^\n;]+);/)?.[1]);
    const suffix = "/.locks/backend/connection.json";
    assert.match(record.slice(tmp.length), /^\/kodex-product-browser--?\d+\/\.locks\/backend\/connection\.json$/);
    assert.ok(record.startsWith(tmp + "/"));
    assert.equal((await stat(record)).mode & 0o777, 0o600);
    const backendHome = record.slice(0, -suffix.length);
    if (mode === "browser") await browser(backendHome); else await tui(backendHome, record);
} catch (error) {
    failure = error;
} finally {
    try {
        await cleanupSteps([
            async () => { await closeTui?.(); socket?.close(); },
            async () => { if (frontend) await stopOwned(frontend, { label: "Owned headless browser" }); },
            async () => {
                if (!backend) return;
                if (backend.child.stdin.writable) backend.child.stdin.end("\n");
                await Promise.race([backend.done, delay(5000, undefined, { ref: false })]);
                await stopOwned(backend, { label: "Owned sidebar fixture", expectedCode: 0 });
            },
            () => rm(root, { recursive: true, force: true }),
        ], failure);
    } catch (error) { failure = error; }
}
if (failure) {
    console.error(`FAIL sidebar History Index ${mode} at ${stage ?? "setup"}: ${failure.message}`);
    if ((mode === "browser" || process.env.KODEX_SIDEBAR_INDEX_DIAGNOSTIC === "1") && snapshot) console.error(snapshot());
    process.exitCode = 1;
} else console.log(JSON.stringify({ mode, checks }));

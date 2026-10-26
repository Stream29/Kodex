import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { copyFile, chmod, mkdtemp, mkdir, readFile, readlink, rm, stat, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { setTimeout as delay } from "node:timers/promises";

const executable = resolve(process.argv[2]);
const output = resolve(process.argv[3]);
await mkdir(output, { recursive: true });
const temporary = await mkdtemp(join(tmpdir(), "kodex-local-entry-"));
const home = join(temporary, "home");
const bin = join(temporary, "bin");
const copied = join(bin, "kodex");
const profile = join(temporary, "chrome");
const capture = join(temporary, "launch.private");
const firstProject = join(temporary, "project-a");
const secondProject = join(temporary, "project-b");
const runtime = join(temporary, "runtime");
for (const path of [home, bin, profile, firstProject, secondProject, runtime]) await mkdir(path, { mode: 0o700 });
await copyFile(executable, copied);
await chmod(copied, 0o700);
// This owned launcher records the handoff privately. It does not change the user's MIME handler.
await writeFile(join(bin, "xdg-open"), '#!/bin/sh\numask 077\nprintf "%s" "$1" > "$KODEX_ENTRY_CAPTURE"\n', { mode: 0o700 });
for (const tool of ["node", "java", "javac", "wasm-opt"]) {
    await writeFile(join(bin, tool), '#!/bin/sh\n: > "$KODEX_ENTRY_TOOL_ATTEMPT"\nexit 97\n', { mode: 0o700 });
}
const env = {
    ...process.env, HOME: home, USERPROFILE: home, XDG_CONFIG_HOME: join(home, ".config"),
    XDG_DATA_HOME: join(home, ".local/share"), XDG_CACHE_HOME: join(home, ".cache"),
    PATH: `${bin}:/usr/bin:/bin`, KODEX_ENTRY_CAPTURE: capture, TMPDIR: "/tmp",
    KODEX_ENTRY_TOOL_ATTEMPT: join(temporary, "tool-attempt"),
    XDG_RUNTIME_DIR: runtime, GDK_BACKEND: "wayland",
};
for (const name of ["JAVA_HOME", "DISPLAY", "WAYLAND_DISPLAY", "SWAYSOCK", "BROWSER"]) delete env[name];
const recordPath = join(home, ".kodex/.locks/backend/connection.json");
const checks = {};
const visualChecks = {};
const requests = [];
const welcomes = [];
const failures = [];
const pending = new Map();
let next = 0;
let socket;
let chrome;
let chromeExit;
let safeToRemove = false;
const frontendHosts = [];

async function command(args, cwd = firstProject, expectedCode = 0) {
    const child = spawn(copied, args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    let text = "";
    child.stdout.on("data", bytes => { text += bytes; });
    child.stderr.on("data", bytes => { text += bytes; });
    const code = await new Promise((done, fail) => { child.once("exit", done); child.once("error", fail); });
    assert.equal(code, expectedCode, `Owned kodex ${args.join(" ")} had an unexpected exit; private command output is not printed.`);
    return text;
}
async function wait(read, matches, label, duration = 30_000) {
    const until = Date.now() + duration;
    while (Date.now() < until) {
        const result = await read();
        if (matches(result)) return result;
        await delay(100);
    }
    throw new Error(`${label} timed out`);
}
async function record() { return JSON.parse(await readFile(recordPath, "utf8")); }
async function status(entry) {
    try {
        const reply = await fetch(`http://127.0.0.1:${entry.port}/control/status`, {
            headers: { Authorization: `Bearer ${entry.password}`, "X-Kodex-Instance": entry.instanceId },
        });
        return reply.ok ? await reply.json() : null;
    } catch { return null; }
}
async function backendAlive(entry) {
    if (await readlink(`/proc/${entry.pid}/exe`).catch(() => null) !== copied) return false;
    const state = await readFile(`/proc/${entry.pid}/status`, "utf8").catch(() => "");
    return !/^State:\s+Z/m.test(state);
}
async function launch(cwd) {
    await rm(capture, { force: true });
    const child = spawn(copied, ["web"], { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    let text = "";
    const exited = new Promise((done, fail) => { child.once("exit", done); child.once("error", fail); });
    child.stdout.on("data", bytes => { text += bytes; });
    child.stderr.on("data", bytes => { text += bytes; });
    const host = { child, exited };
    frontendHosts.push(host);
    const url = await wait(() => readFile(capture, "utf8").catch(() => ""), value => value.includes("#"), "frontend host launch");
    assert.match(url, /^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/);
    const current = await record();
    assert.ok(!text.includes(current.password) && !text.includes(url) && !text.includes(url.split("#")[1]));
    assert.equal((await status(current)).phase, "ready");
    assert.notEqual(new URL(url).port, String(current.port));
    assert.equal(child.exitCode, null, "web must stay alive as the foreground frontend host");
    return { url, current, cwd, host };
}
function send(method, params = {}, sessionId) {
    return new Promise((done, fail) => {
        const id = ++next;
        const timer = setTimeout(() => { pending.delete(id); fail(new Error(`${method} timed out`)); }, 20_000);
        pending.set(id, { done, fail, timer });
        socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
}
async function evaluate(session, expression) {
    const result = await send("Runtime.evaluate", { expression, returnByValue: true, userGesture: true }, session);
    assert.equal(result.exceptionDetails, undefined, "Browser evaluation failed; private values are not printed.");
    return result.result.value;
}
async function text(session) {
    return (await send("Accessibility.getFullAXTree", {}, session)).nodes.map(node => node.name?.value ?? "").join("\n");
}
async function connected(session, entry, welcomeStart = 0) {
    await wait(() => text(session), value => value.includes("Backend: Connected"), "automatic connection");
    await wait(() => welcomes.slice(welcomeStart).filter(item => item.session === session),
        values => values.some(item => item.directory === entry.cwd && item.instance === entry.current.instanceId),
        "original business welcome with bound directory");
    assert.equal(await evaluate(session, "location.hash"), "");
    assert.ok(!(await text(session)).includes("Choose private connection record"));
    const cookies = (await send("Network.getCookies", {}, session)).cookies
        .filter(cookie => cookie.name === "kodex_local_web");
    assert.equal(cookies.length, 0, "Neither frontend nor backend creates a local-entry cookie");
    assert.equal(await evaluate(session, `sessionStorage.getItem('kodex.frontend-capability:' + location.pathname) !== null`), true);
    assert.ok(requests.includes(`ws://127.0.0.1:${entry.current.port}/browser/rpc`));
    assert.ok(!requests.some(url => url.endsWith(new URL(entry.url).pathname + "rpc")));
}
async function open(entry) {
    const target = await send("Target.createTarget", { url: "about:blank" });
    const session = (await send("Target.attachToTarget", { targetId: target.targetId, flatten: true })).sessionId;
    for (const domain of ["Runtime", "Page", "Network", "Accessibility"]) await send(`${domain}.enable`, {}, session);
    await send("Page.navigate", { url: entry.url }, session);
    await connected(session, entry);
    return session;
}
async function clickNamed(session, name, role) {
    const nodes = await wait(async () => (await send("Accessibility.getFullAXTree", {}, session)).nodes,
        nodes => nodes.some(node => node.name?.value === name && node.backendDOMNodeId &&
            (!role || node.role?.value === role)), `visible control ${name}`);
    const node = nodes.find(node => node.name?.value === name && node.backendDOMNodeId &&
        (!role || node.role?.value === role));
    const { model } = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }, session);
    const points = model.content;
    const position = { x: (points[0] + points[2] + points[4] + points[6]) / 4,
        y: (points[1] + points[3] + points[5] + points[7]) / 4 };
    await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...position }, session);
    for (const type of ["mousePressed", "mouseReleased"])
        await send("Input.dispatchMouseEvent", { type, ...position, button: "left", clickCount: 1 }, session);
}
async function captureVisual(session, name) {
    let previous, stable = 0;
    const data = await wait(async () => (await send("Page.captureScreenshot", { format: "png" }, session)).data,
        image => {
            stable = image === previous ? stable + 1 : 0;
            previous = image;
            return stable >= 2;
        }, "Material scene pixels settled", 10_000);
    await writeFile(join(output, `${name}.png`), Buffer.from(data, "base64"));
}
async function visualAcceptance(session) {
    await send("Emulation.setDeviceMetricsOverride", {
        width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false,
    }, session);
    await wait(() => text(session), value => value.includes("Start a new session"), "new session layout");
    await captureVisual(session, "material-entry-wide");
    await clickNamed(session, "Settings", "button");
    await wait(() => text(session), value => value.includes("Settings page General"), "settings navigation");
    await clickNamed(session, "Settings page General");
    await wait(() => text(session), value => value.includes("Input"), "settings general page");
    await captureVisual(session, "material-settings-wide");
    await clickNamed(session, "Settings page Hooks");
    await wait(() => text(session), value => value.includes("Frontend errors"), "hooks page");
    await clickNamed(session, "Add", "button");
    await wait(() => text(session), value => value.includes("Command") && value.includes("Name"), "hook fields");
    await captureVisual(session, "material-hook-editor");
    for (const type of ["keyDown", "keyUp"])
        await send("Input.dispatchKeyEvent", { type, key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 }, session);
    await wait(() => text(session), value => !value.includes("Command") && value.includes("Frontend errors"), "hook dismissal");
    await clickNamed(session, "Close", "button");
    await wait(() => text(session), value => value.includes("Start a new session") && !value.includes("Settings page Hooks"),
        "settings dismissal");
    visualChecks.wideSettingsAndRealHookEditor = true;
    await send("Emulation.setDeviceMetricsOverride", {
        width: 420, height: 900, deviceScaleFactor: 1, mobile: false,
    }, session);
    await captureVisual(session, "material-entry-compact");
    await clickNamed(session, "Settings", "button");
    await wait(() => text(session), value => value.includes("Settings page General"), "compact settings navigation");
    await clickNamed(session, "Settings page General");
    await wait(() => text(session), value => value.includes("Input"), "compact settings page");
    await captureVisual(session, "material-settings-compact");
    await clickNamed(session, "Close", "button");
    await wait(() => text(session), value => !value.includes("Settings page General"), "compact settings dismissal");
    visualChecks.compactLayoutAndDismissal = true;
    await send("Emulation.setDeviceMetricsOverride", {
        width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false,
    }, session);
    await send("Emulation.setEmulatedMedia", {
        features: [{ name: "prefers-color-scheme", value: "dark" }],
    }, session);
    await captureVisual(session, "material-entry-dark");
    await send("Emulation.setEmulatedMedia", {
        features: [{ name: "prefers-color-scheme", value: "light" }],
    }, session);
    visualChecks.lightAndDarkFramesRecorded = true;
    await writeFile(join(output, "material-visuals.json"), JSON.stringify(visualChecks, null, 2));
}
async function closeBrowser() {
    if (!chrome) return;
    if (socket?.readyState === WebSocket.OPEN) await send("Browser.close").catch(() => {});
    chrome.kill("SIGTERM");
    await chromeExit;
    chrome = null;
    socket?.close();
    for (const request of pending.values()) { clearTimeout(request.timer); request.fail(new Error("Owned browser closed")); }
    pending.clear();
}
async function stopHost(host) {
    if (host.child.exitCode !== null) return;
    host.child.kill("SIGTERM");
    const code = await Promise.race([host.exited, delay(10_000).then(() => { throw Error("Frontend host cleanup timed out"); })]);
    assert.equal(code, 130);
}

try {
    assert.match(await command(["--help"]), /kodex web.*kodex app/);
    checks.copiedExecutableWithoutAdjacentAssets = true;
    const first = await launch(firstProject);
    assert.doesNotMatch(await readFile(`/proc/${first.current.pid}/maps`, "utf8"), /libgtk|libwebkit|libgobject/);
    checks.independentBackendBootstrapped = true;
    checks.backendDoesNotLoadGuiLibraries = true;
    assert.match(await command(["app"], firstProject, 1), /Cannot initialize GTK display/);
    assert.equal((await status(first.current)).phase, "ready");
    checks.noDisplayAppFailurePreservesBackend = true;
    chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
        "--headless=new", "--disable-dev-shm-usage", "--no-first-run", "--no-default-browser-check",
        "--disable-background-networking", "--disable-component-update", "--remote-debugging-address=127.0.0.1",
        "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--force-renderer-accessibility",
        "--window-size=1280,1000", "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
        "about:blank",
    ], { stdio: "ignore" });
    chromeExit = new Promise(done => chrome.once("exit", done));
    const active = await wait(() => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""),
        value => value.includes("\n"), "owned Chrome startup");
    const [port, path] = active.trim().split("\n");
    socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
    socket.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        if (message.id) {
            const request = pending.get(message.id);
            if (!request) return;
            clearTimeout(request.timer); pending.delete(message.id);
            if (message.error) request.fail(new Error("Owned browser protocol request failed"));
            else request.done(message.result);
        } else {
            if (message.method === "Runtime.exceptionThrown") failures.push("Unhandled browser exception");
            if (message.method === "Network.requestWillBeSent") requests.push(message.params.request.url);
            if (message.method === "Network.webSocketCreated") requests.push(message.params.url);
            if (message.method === "Network.webSocketFrameReceived") {
                try {
                    const hello = JSON.parse(message.params.response.payloadData);
                    if (hello.workingDirectory) welcomes.push({
                        session: message.sessionId, directory: hello.workingDirectory, instance: hello.instanceId,
                    });
                } catch { /* Business frames are not retained. */ }
            }
        }
    });
    await new Promise((done, fail) => {
        socket.addEventListener("open", done, { once: true }); socket.addEventListener("error", fail, { once: true });
    });
    const session = await open(first);
    checks.frontendHostedBootstrapAndDirectBackendRpc = true;
    if (process.env.KODEX_ENTRY_VISUALS === "1") await visualAcceptance(session);
    const bootstrapCount = requests.filter(url => url.endsWith("/bootstrap")).length;
    const oldTimeOrigin = await evaluate(session, "performance.timeOrigin");
    const welcomeStart = welcomes.length;
    await send("Page.reload", {}, session);
    await wait(() => evaluate(session, "performance.timeOrigin").catch(() => null),
        value => value != null && value !== oldTimeOrigin, "reload document replacement");
    await connected(session, first, welcomeStart);
    assert.equal(requests.filter(url => url.endsWith("/bootstrap")).length, bootstrapCount + 1);
    checks.reloadUsesTabScopedCapabilityWithoutCookie = true;
    const second = await launch(secondProject);
    assert.equal(second.current.instanceId, first.current.instanceId);
    assert.notEqual(new URL(second.url).pathname, new URL(first.url).pathname);
    const secondSession = await open(second);
    checks.existingBackendAndIndependentDirectoryContexts = true;
    const screenshot = await send("Page.captureScreenshot", {}, secondSession);
    await writeFile(join(output, "local-entry.png"), Buffer.from(screenshot.data, "base64"));
    for (const entry of [first, second]) {
        const ticket = entry.url.split("#")[1];
        assert.ok(requests.every(url => !url.includes(ticket) && !url.includes(entry.current.password)),
            "No ticket or Native master credential is sent in HTTP request URLs.");
    }
    checks.fragmentClearedAndNoCredentialInRequestUrls = true;
    assert.equal(await stat(env.KODEX_ENTRY_TOOL_ATTEMPT).catch(() => null), null);
    checks.runtimeToolchainCommandsNotInvoked = true;
    await closeBrowser();
    assert.equal((await status(first.current)).phase, "ready");
    checks.browserExitDoesNotStopBackend = true;
    for (const entry of [first, second]) {
        assert.equal(entry.host.child.exitCode, null, "Closing browser leaves web host alive for refresh");
        const url = new URL(entry.url);
        const bootstrap = await fetch(new URL("bootstrap", url), {
            method: "POST", headers: { Origin: url.origin, "Content-Type": "application/json" },
            body: JSON.stringify({ ticket: url.hash.slice(1) }),
        });
        assert.equal(bootstrap.status, 200);
        const info = await bootstrap.json();
        assert.notEqual(info.endpoint.password, entry.current.password);
        const denied = await fetch(`http://127.0.0.1:${entry.current.port}/control/status`, {
            headers: { Authorization: `Bearer ${info.endpoint.password}`, "X-Kodex-Instance": entry.current.instanceId },
        });
        assert.equal(denied.status, 401, "Scoped browser credential must not authorize management");
        await stopHost(entry.host);
        assert.equal(await fetch(new URL(url.pathname, url)).then(() => true, () => false), false);
        const revoked = await fetch(`http://127.0.0.1:${entry.current.port}/heartbeat`, {
            method: "POST", headers: {
                Origin: url.origin, Authorization: `Bearer ${info.endpoint.password}`,
                "X-Kodex-Instance": entry.current.instanceId, "X-Kodex-Protocol": entry.current.businessProtocol,
                "X-Kodex-Browser-Protocol": "1",
            },
        });
        assert.ok([401, 403].includes(revoked.status), "Host shutdown must revoke browser admission");
    }
    assert.equal((await status(first.current)).phase, "ready");
    checks.frontendHostExitReleasesOnlyFrontendResources = true;
    await command(["backend", "stop"]);
    await wait(() => backendAlive(first.current), value => !value, "owned backend cooperative cleanup");
    safeToRemove = true;
    assert.deepEqual(failures, []);
    await writeFile(join(output, "local-entry.json"), JSON.stringify({ checks, failures,
        launcher: "owned xdg-open handoff adapter; not the user's default browser configuration",
    }, null, 2));
    console.log(JSON.stringify({ checks, failures }));
} finally {
    await closeBrowser();
    for (const host of frontendHosts) await stopHost(host);
    if (!safeToRemove) {
        const current = await record().catch(() => null);
        if (current && await backendAlive(current)) {
            await command(["backend", "stop"]).catch(() => {});
            safeToRemove = await wait(() => backendAlive(current), value => !value, "owned backend cleanup").then(() => true).catch(() => false);
        } else safeToRemove = true;
    }
    if (safeToRemove) await rm(temporary, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    else throw new Error("Owned backend cleanup is incomplete; its temporary Home was retained.");
}

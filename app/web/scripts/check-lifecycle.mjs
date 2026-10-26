import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const fixture = resolve(process.argv[2]);
assert.match(fixture, /\/app\/web\/test-support\/backend\/build\/bin\/(?:linuxX64|macosArm64)\/browserDebugExecutable\/kodex-product-browser-fixture\.kexe$/,
  "Use only the temporary-Home/offline production-host fixture, never an installed backend.");
const website = resolve(process.argv[3]);
const output = resolve(process.argv[4]);
const url = "http://127.0.0.1:18795/";
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(output, "owned-chrome-"));
const pages = new Map();
const pending = new Map();
const ready = [];
const errors = [];
const exceptionDetails = [];
const knownPasswords = new Set();
const checks = {};
const restartOnly = process.env.KODEX_LIFECYCLE_RESTART_ONLY === "1";
const freezeOnly = process.env.KODEX_LIFECYCLE_FREEZE_ONLY === "1";
assert.ok(!(restartOnly && freezeOnly), "Choose at most one targeted lifecycle mode.");
let phase = "startup";
let next = 0;
let socket;
let native;
let nativeExit;
let staticHost;
let staticExit;
let chrome;
let chromeExit;
let recordPath;
let home;
let staticReady = false;

function lines(stream, consume) {
  let buffer = "";
  stream.on("data", bytes => {
    buffer += bytes.toString();
    let end;
    while ((end = buffer.indexOf("\n")) >= 0) {
      consume(buffer.slice(0, end));
      buffer = buffer.slice(end + 1);
    }
  });
}
async function wait(read, matches, label, duration = 30_000, interval = 100) {
  const until = Date.now() + duration;
  while (Date.now() < until) {
    const value = await read();
    if (matches(value)) return value;
    await delay(interval);
  }
  throw new Error(`${label} timed out`);
}
function send(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++next;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 20_000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
async function readRecord(index) {
  await wait(async () => ready[index], Boolean, `fixture ready ${index}`);
  const path = ready[index];
  if (recordPath) assert.equal(path, recordPath, "Restart must retain only the fixture-owned Home.");
  recordPath = path;
  home = path.slice(0, -"/.locks/backend/connection.json".length);
  assert.equal((await stat(path)).mode & 0o777, 0o600);
  const record = JSON.parse(await readFile(path, "utf8"));
  knownPasswords.add(record.password);
  return record;
}
async function status(record) {
  try {
    const response = await fetch(`http://127.0.0.1:${record.port}/control/status`, {
      headers: { Authorization: `Bearer ${record.password}`, "X-Kodex-Instance": record.instanceId },
      signal: AbortSignal.timeout(2_000),
    });
    if (!response.ok) return null;
    return await response.json();
  } catch { return null; }
}
async function stop(child, exit, input) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
  if (input !== undefined) child.stdin.end(input);
  else child.kill("SIGTERM");
  await Promise.race([exit, delay(10_000)]);
  if (child.exitCode === null && child.signalCode === null) { child.kill("SIGKILL"); await exit; }
}
const names = nodes => nodes.map(n => n.name?.value ?? "").join("\n");
function historyText(nodes, text) {
  const byId = new Map(nodes.map(n => [n.nodeId, n]));
  return nodes.filter(n => n.role?.value === "StaticText" && n.name?.value === text).filter(node => {
    for (let parent = byId.get(node.parentId); parent; parent = byId.get(parent.parentId))
      if (parent.name?.value?.startsWith("History entry ")) return true;
    return false;
  }).length;
}
class Page {
  constructor(targetId, sessionId, contextId) {
    Object.assign(this, { targetId, sessionId, contextId, chooser: null, heartbeats: 0, heartbeatReplies: 0, peers: [] });
  }
  send(method, params = {}) { return send(method, params, this.sessionId); }
  async evaluate(expression) {
    const value = await this.send("Runtime.evaluate", { expression, returnByValue: true, userGesture: true });
    assert.equal(value.exceptionDetails, undefined);
    return value.result.value;
  }
  async tree() { return (await this.send("Accessibility.getFullAXTree")).nodes; }
  async has(text) { return wait(() => this.tree(), nodes => names(nodes).includes(text), text); }
  async clickMatching(matches, label) {
    const nodes = await wait(() => this.tree(), nodes => nodes.some(matches), label);
    const node = nodes.find(matches);
    assert.ok(node.backendDOMNodeId);
    const { object } = await this.send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId });
    try {
      await this.send("Runtime.callFunctionOn", {
        objectId: object.objectId, functionDeclaration: "function() { this.click(); }", userGesture: true,
      });
    } finally { await this.send("Runtime.releaseObject", { objectId: object.objectId }); }
  }
  click(name) { return this.clickMatching(n => n.role?.value === "button" && n.name?.value === name, name); }
  async editor() {
    return this.evaluate(`(() => {
      const values = [];
      function visit(root) {
        for (const e of root.querySelectorAll('*')) {
          if (e.tagName === 'TEXTAREA') values.push({value:e.value, active:e.getRootNode().activeElement===e, disabled:e.disabled});
          if (e.shadowRoot) visit(e.shadowRoot);
        }
      }
      visit(document); return values;
    })()`);
  }
  async key(key, code) {
    for (const type of ["keyDown", "keyUp"])
      await this.send("Input.dispatchKeyEvent", {
        type, key, code: key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code,
        ...(type === "keyDown" && key === "Enter" ? { text: "\r", unmodifiedText: "\r" } : {}),
      });
  }
  async type(text) {
    await wait(() => this.editor(), values => values.length === 1 && values[0].active && !values[0].disabled, "focused editor");
    await this.send("Input.insertText", { text });
    await wait(() => this.editor(), values => values[0]?.value === text, "exact draft");
  }
  async importRecord() {
    this.chooser = null;
    await this.send("Page.setInterceptFileChooserDialog", { enabled: true });
    await this.click("Choose private connection record");
    await wait(async () => this.chooser, Boolean, "explicit file chooser");
    await this.send("DOM.setFileInputFiles", { backendNodeId: this.chooser.backendNodeId, files: [recordPath] });
    await this.has("Backend: Connected");
    await wait(() => this.editor(), values => values.length === 1 && values[0].active, "mounted shared root");
  }
  async openOnlySession() {
    await this.click("Sessions");
    await this.has("Show archived");
    const nodes = await wait(() => this.tree(), nodes => nodes.some(n => n.name?.value?.startsWith("Actions for session ")), "persisted catalog row");
    const actions = nodes.filter(n => n.role?.value === "button" && n.name?.value?.startsWith("Actions for session "));
    assert.equal(actions.length, 1);
    const index = actions[0].name.value.slice("Actions for session ".length);
    await this.clickMatching(n => n.role?.value === "button" && n.name?.value?.startsWith(`Session ${index}`),
      "open the exact untitled fixture session");
    await wait(() => this.tree(), nodes => historyText(nodes, "lifecycle shared history") === 1, "persisted history after navigation");
    await wait(() => this.editor(), values => values.length === 1 && values[0].active, "Agent editor");
  }
  async disconnectIfMounted() {
    const nodes = await this.tree();
    if (nodes.some(n => n.role?.value === "button" && n.name?.value === "Disconnect")) await this.click("Disconnect");
    try { await this.has("Choose private connection record"); }
    catch (failure) {
      // Diagnostic only: physical success must not turn a failed AX action into a pass.
      const nodes = await this.tree();
      const disconnect = nodes.find(n => n.role?.value === "button" && n.name?.value === "Disconnect");
      if (disconnect?.backendDOMNodeId) {
        const { model } = await this.send("DOM.getBoxModel", { backendNodeId: disconnect.backendDOMNodeId });
        const q = model.content;
        const point = { x: (q[0] + q[2] + q[4] + q[6]) / 4, y: (q[1] + q[3] + q[5] + q[7]) / 4 };
        for (const type of ["mouseMoved", "mousePressed", "mouseReleased"])
          await this.send("Input.dispatchMouseEvent", { ...point, type, button: "left", clickCount: 1 });
        await delay(1_000);
        await writeFile(join(output, "disconnect-diagnostic.json"), JSON.stringify({
          physicalChooserVisible: names(await this.tree()).includes("Choose private connection record"),
          editors: await this.editor(),
        }, null, 2));
      }
      throw failure;
    }
  }
  async close() {
    await send("Target.disposeBrowserContext", { browserContextId: this.contextId });
  }
}
async function page() {
  const { browserContextId } = await send("Target.createBrowserContext", { disposeOnDetach: true });
  const { targetId } = await send("Target.createTarget", { url: "about:blank", browserContextId });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  const result = new Page(targetId, sessionId, browserContextId);
  pages.set(sessionId, result);
  for (const domain of ["Runtime", "Page", "Network", "Accessibility"]) await result.send(`${domain}.enable`);
  await result.send("Page.navigate", { url });
  await result.importRecord();
  return result;
}

try {
  staticHost = spawn(process.execPath, [
    fileURLToPath(new URL("../../test-support/web-renderer/scripts/serve.mjs", import.meta.url)), website,
  ], { stdio: ["ignore", "pipe", "pipe"] });
  staticExit = new Promise(done => staticHost.once("exit", done));
  staticHost.on("error", () => errors.push("Owned static process failed to start"));
  lines(staticHost.stdout, line => { if (line.startsWith("STATIC_FIXTURE_READY")) staticReady = true; });
  staticHost.stderr.resume();
  await wait(async () => staticReady, Boolean, "owned static server", 10_000);
  native = spawn(fixture, [], {
    env: { ...process.env, TMPDIR: "/tmp", KODEX_BROWSER_FIXTURE_DELAY_MS: "300" },
    stdio: ["pipe", "pipe", "pipe"],
  });
  nativeExit = new Promise(done => native.once("exit", done));
  native.on("error", () => errors.push("Owned fixture process failed to start"));
  lines(native.stdout, line => {
    const match = line.match(/^PRODUCT_BROWSER_READY (\/tmp\/kodex-product-browser--?\d+\/\.locks\/backend\/connection\.json);/);
    if (match) ready.push(match[1]);
  });
  native.stderr.resume();
  let record = await readRecord(0);
  chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
    "--headless=new", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update",
    "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
    "--no-first-run", "--no-default-browser-check", "--window-size=1280,1000", "--force-renderer-accessibility",
    "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "about:blank",
  ], { stdio: "ignore" });
  chromeExit = new Promise(done => chrome.once("exit", done));
  chrome.on("error", () => errors.push("Owned browser process failed to start"));
  const active = await wait(() => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""),
    value => value.includes("\n"), "owned Chrome");
  const [port, path] = active.trim().split("\n");
  socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  socket.addEventListener("message", event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const request = pending.get(value.id);
      if (!request) return;
      clearTimeout(request.timer);
      pending.delete(value.id);
      if (value.error) request.reject(new Error(JSON.stringify(value.error)));
      else request.resolve(value.result);
      return;
    }
    const page = pages.get(value.sessionId);
    if (!page) return;
    if (value.method === "Page.fileChooserOpened") page.chooser = value.params;
    if (value.method === "Network.webSocketCreated") page.peers.push(value.params.url);
    if (value.method === "Network.requestWillBeSent" && value.params.request.url.endsWith("/heartbeat")) page.heartbeats++;
    if (value.method === "Network.responseReceived" && value.params.response.url.endsWith("/heartbeat") &&
      value.params.response.status === 200) page.heartbeatReplies++;
    if (value.method === "Runtime.exceptionThrown") {
      errors.push("Unhandled application exception");
      let description = value.params.exceptionDetails.exception?.description ?? "";
      for (const password of knownPasswords) description = description.replaceAll(password, "<redacted>");
      exceptionDetails.push({
        phase, text: value.params.exceptionDetails.text, description: description.slice(0, 10_000),
        frames: value.params.exceptionDetails.stackTrace?.callFrames,
      });
    }
  });
  await new Promise(done => socket.addEventListener("open", done, { once: true }));
  const first = await page();
  await first.type("lifecycle shared history");
  await first.key("Enter", 13);
  await first.has("test answer — 你好");
  await first.type("unsent first-tab draft");
  const second = await page();
  await second.openOnlySession();
  assert.equal((await second.editor())[0].value, "", "Different frontends must not share their local Composer draft.");
  await second.type("second-tab draft");
  assert.equal((await first.editor())[0].value, "unsent first-tab draft");
  const secondReplies = second.heartbeatReplies;
  await first.close();
  await delay(1_000);
  const closedHeartbeats = first.heartbeats;
  if (!restartOnly && !freezeOnly) {
    console.log("Checking independent frontend renewal for longer than the configured 60s lease");
    await delay(65_000);
    assert.equal(first.heartbeats, closedHeartbeats);
    assert.ok(second.heartbeatReplies > secondReplies, "The surviving page must receive accepted renewals.");
    assert.equal((await status(record))?.phase, "ready");
    assert.equal((await second.editor())[0].value, "second-tab draft");
    checks.independentFrontendLifetime = true;
  }

  phase = "restart";
  const old = record;
  native.stdin.write("restart\n");
  record = await readRecord(1);
  assert.notEqual(record.instanceId, old.instanceId);
  await delay(1_000);
  assert.ok(second.peers.every(peer => peer === `ws://127.0.0.1:${old.port}/browser/rpc`),
    "An old frontend must never discover or retarget to the replacement instance.");
  phase = "restart-disconnect";
  await second.disconnectIfMounted();
  await second.importRecord();
  assert.equal((await second.editor())[0].value, "", "Explicit new import must not submit or retarget the old draft.");
  await second.openOnlySession();
  assert.equal(historyText(await second.tree(), "lifecycle shared history"), 1);
  checks.restartRequiresExplicitImport = true;
  checks.persistedHistorySurvivesInstanceRestart = true;

  let expiryMilliseconds = null;
  if (!restartOnly) {
  phase = "freeze";
  await second.type("frozen-tab draft");
  await second.send("Page.setWebLifecycleState", { state: "frozen" });
  await delay(1_000);
  const frozenHeartbeats = second.heartbeats;
  const frozenAt = Date.now();
  console.log("Checking real idle expiry while the only frontend is frozen");
  await wait(() => status(record), value => value === null || value.phase !== "ready", "backend idle expiry", 85_000, 1_000);
  expiryMilliseconds = Date.now() - frozenAt;
  assert.ok(expiryMilliseconds >= 45_000, "A premature host failure is not a lease-expiry pass.");
  assert.equal(second.heartbeats, frozenHeartbeats, "A frozen renderer cannot promise continued renewal.");
  checks.frozenPageAllowsIdleExpiry = true;
  await second.send("Page.setWebLifecycleState", { state: "active" });
  const beforeForeground = await second.evaluate("({visibility:document.visibilityState, focused:document.hasFocus()})");
  // Unfreezing may leave the document hidden. Returning to the tab is a separate user action.
  await second.send("Page.bringToFront");
  const broughtToFront = await second.evaluate("({visibility:document.visibilityState, focused:document.hasFocus()})");
  // Headless Chrome keeps even a plain HTML control hidden after this forced freeze/resume.
  // Restore the visible/focused test host only after real idle expiry, never during the freeze.
  await second.send("Emulation.setFocusEmulationEnabled", { enabled: true });
  const afterForeground = await wait(
    () => second.evaluate("({visibility:document.visibilityState, focused:document.hasFocus()})"),
    state => state.visibility === "visible" && state.focused, "foreground page after resume",
  );
  await writeFile(join(output, "resume-foreground.json"), JSON.stringify({ beforeForeground, broughtToFront, afterForeground }, null, 2));
  native.stdin.write("restart\n");
  record = await readRecord(2);
  await second.disconnectIfMounted();
  await second.importRecord();
  await second.openOnlySession();
  assert.equal((await status(record))?.phase, "ready");
  checks.explicitRecoveryAfterFreeze = true;
  }
  assert.deepEqual(errors, []);
  await writeFile(join(output, "lifecycle-result.json"), JSON.stringify({
    browser: (await send("Browser.getVersion")).product, checks, expiryMilliseconds,
    closedPageHeartbeatCount: closedHeartbeats, survivingPageAcceptedRenewals: second.heartbeatReplies,
    errors, mode: restartOnly ? "restart-only" : freezeOnly ? "freeze-only" : "full",
    restartKind: "production host instances in the same test process and Home",
  }, null, 2));
  console.log(restartOnly ? "PASS targeted exact-instance restart and explicit import" :
    freezeOnly ? "PASS targeted frozen-page expiry and explicit recovery" :
      "PASS multi-frontend renewal, exact-instance restart, frozen-page expiry and explicit recovery");
} catch (failure) {
  await writeFile(join(output, "lifecycle-partial.json"), JSON.stringify({ phase, checks, errors, exceptionDetails }, null, 2));
  for (const [id, page] of pages) {
    try {
      const image = await page.send("Page.captureScreenshot", { format: "png" });
      await writeFile(join(output, `failure-${id}.png`), Buffer.from(image.data, "base64"));
      await writeFile(join(output, `failure-${id}.json`), JSON.stringify(await page.tree(), null, 2));
    } catch { /* Closed or frozen targets need not answer diagnostic requests. */ }
  }
  throw failure;
} finally {
  socket?.close();
  await stop(chrome, chromeExit);
  await stop(native, nativeExit, "\n");
  await stop(staticHost, staticExit);
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  if (home) await rm(home, { recursive: true, force: true });
}

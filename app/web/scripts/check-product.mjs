import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { mkdtemp, mkdir, readFile, rm, writeFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const require = createRequire(resolve(process.argv[2]));
const WebSocket = require("ws");
const output = resolve(process.argv[3]);
const record = process.env.KODEX_BROWSER_RECORD ?? "";
assert.match(record, /^\/tmp\/kodex-product-browser--?\d+\/\.locks\/backend\/connection\.json$/,
  "Only the production-host fixture's temporary private record may be used.");
const privateRecord = JSON.parse(await readFile(record, "utf8"));
assert.equal((await stat(record)).mode & 0o777, 0o600, "The production host must protect its private connection record.");
const url = "http://127.0.0.1:18795/";
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(output, "owned-chrome-"));
const chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
  "--headless=new", "--disable-dev-shm-usage", "--remote-debugging-address=127.0.0.1",
  "--disable-background-networking", "--disable-component-update", "--no-first-run", "--no-default-browser-check",
  "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--window-size=1280,1000",
  "--force-renderer-accessibility", "--use-gl=angle", "--use-angle=swiftshader",
  "--enable-unsafe-swiftshader", "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });
let chromeErrors = "";
chrome.stderr.on("data", bytes => { chromeErrors = (chromeErrors + bytes).slice(-65_536); });
const exited = new Promise(done => chrome.once("exit", done));
let socket;
let next = 0;
const pending = new Map();
const events = [];
const errors = [];
const sockets = [];
const requests = [];
const fonts = new Set();
const disabledAccessibility = [];
const measurements = {};
let sessionId;

function send(method, params = {}) {
  const id = ++next;
  return new Promise((done, fail) => {
    const timeout = setTimeout(() => { pending.delete(id); fail(new Error(`${method} timed out`)); }, 20_000);
    pending.set(id, { done, fail, timeout });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
async function wait(read, matches, label, milliseconds = 30_000) {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    const value = await read();
    if (matches(value)) return value;
    await delay(100);
  }
  throw new Error(`${label} timed out`);
}
async function evaluate(expression) {
  const value = await send("Runtime.evaluate", { expression, returnByValue: true, userGesture: true });
  assert.equal(value.exceptionDetails, undefined);
  return value.result.value;
}
async function tree() { return (await send("Accessibility.getFullAXTree")).nodes; }
function text(nodes) { return nodes.map(node => node.name?.value ?? "").join("\n"); }
async function waitText(value) { return wait(tree, nodes => text(nodes).includes(value), value); }
async function button(name) {
  const nodes = await wait(tree, nodes => nodes.some(node => node.role?.value === "button" && node.name?.value === name), name);
  return nodes.find(node => node.role?.value === "button" && node.name?.value === name);
}
async function click(name) {
  const nodes = await wait(tree, nodes => nodes.some(node => node.name?.value === name && node.backendDOMNodeId), name);
  const node = nodes.find(node => node.name?.value === name && node.backendDOMNodeId);
  const { object } = await send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId });
  try {
    await send("Runtime.callFunctionOn", {
      objectId: object.objectId, functionDeclaration: "function() { this.click(); }", userGesture: true,
    });
  } finally { await send("Runtime.releaseObject", { objectId: object.objectId }); }
}
async function editor() {
  return evaluate(`(() => {
    const found = [];
    function visit(root) {
      for (const e of root.querySelectorAll('*')) {
        if (e.tagName === 'TEXTAREA') found.push({ value: e.value, cursor: e.selectionEnd,
          active: e.getRootNode().activeElement === e, disabled: e.disabled });
        if (e.shadowRoot) visit(e.shadowRoot);
      }
    }
    visit(document); return found;
  })()`);
}
async function key(name, code) {
  for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", {
    type, key: name, code: name, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code,
    ...(type === "keyDown" && name === "Enter" ? { text: "\r", unmodifiedText: "\r" } : {}),
  });
}
async function position(node) {
  const { model } = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId });
  const q = model.content;
  return { x: (q[0] + q[2] + q[4] + q[6]) / 4, y: (q[1] + q[3] + q[5] + q[7]) / 4 };
}
async function pointer(point) {
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent", {
    ...point, type, button: "left", clickCount: 1,
  });
}
async function importRecord() {
  await send("Page.setInterceptFileChooserDialog", { enabled: true });
  const mark = events.length;
  await click("Choose private connection record");
  const event = await wait(() => events.slice(mark).find(e => e.method === "Page.fileChooserOpened"), Boolean, "file chooser");
  await send("DOM.setFileInputFiles", { backendNodeId: event.params.backendNodeId, files: [record] });
  await waitText("Backend: Connected");
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "shared root mounted");
}
async function status() {
  const response = await fetch(`http://127.0.0.1:${privateRecord.port}/control/status`, {
    headers: { Authorization: `Bearer ${privateRecord.password}`, "X-Kodex-Instance": privateRecord.instanceId },
  });
  assert.equal(response.status, 200);
  return response.json();
}

try {
  const active = await wait(() => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""),
    value => value.includes("\n"), "owned Chrome startup");
  const [port, path] = active.trim().split("\n");
  socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  socket.on("message", bytes => {
    const message = JSON.parse(bytes);
    if (message.id) {
      const request = pending.get(message.id);
      if (!request) return;
      clearTimeout(request.timeout); pending.delete(message.id);
      if (message.error) request.fail(new Error(JSON.stringify(message.error)));
      else request.done(message.result);
    } else {
      events.push(message);
      if (message.method === "Runtime.exceptionThrown") errors.push("Unhandled browser exception");
      if (message.method === "Network.webSocketCreated") sockets.push(message.params.url);
      if (message.method === "Network.requestWillBeSent") requests.push(message.params.request.url);
      if (message.method === "Network.responseReceived" && message.params.response.status === 200 &&
          message.params.response.url.includes("/font/")) fonts.add(message.params.response.url.split("/").at(-1));
    }
  });
  await new Promise((done, fail) => { socket.once("open", done); socket.once("error", fail); });
  const browser = (await send("Browser.getVersion")).product;
  const target = await send("Target.createTarget", { url: "about:blank" });
  sessionId = (await send("Target.attachToTarget", { targetId: target.targetId, flatten: true })).sessionId;
  for (const domain of ["Runtime", "Network", "Accessibility", "Page", "Performance"]) await send(`${domain}.enable`);
  await send("Page.addScriptToEvaluateOnNewDocument", { source: `(() => {
    const NativeSocket = globalThis.WebSocket;
    const owned = new Set();
    globalThis.WebSocket = new Proxy(NativeSocket, {
      construct(target, args, newTarget) {
        const socket = Reflect.construct(target, args, newTarget);
        owned.add(socket);
        socket.addEventListener('close', () => owned.delete(socket));
        return socket;
      }
    });
    Object.defineProperty(globalThis, '__kodexTestCloseTransport', { value: () => {
      let closed = 0;
      for (const socket of owned) if (new URL(socket.url).pathname === '/browser/rpc') {
        socket.close(1000, 'Isolated transport recovery check'); closed++;
      }
      return closed;
    }});
  })()` });
  await send("Page.navigate", { url });
  const navigationStarted = Date.now();
  await importRecord();
  measurements.navigationToSharedRootIncludingExplicitImportMilliseconds = Date.now() - navigationStarted;
  assert.ok(sockets.every(value => value === `ws://127.0.0.1:${privateRecord.port}/browser/rpc`));
  assert.ok(fonts.has("noto_sans_sc.ttf") && fonts.has("noto_sans_math.ttf"));
  await send("Input.insertText", { text: "abcd" });
  await key("ArrowLeft", 37); await key("ArrowLeft", 37);
  await send("Input.insertText", { text: "X" });
  await wait(editor, inputs => inputs[0]?.value === "abXcd" && inputs[0]?.cursor === 3, "strict cursor");
  const responseStarted = Date.now();
  await key("Enter", 13);
  const answer = await waitText("test answer — 你好");
  measurements.submitToCompletedFixtureResponseMilliseconds = Date.now() - responseStarted;
  assert.equal(answer.filter(node => node.role?.value === "StaticText" && node.name?.value === "abXcd").length, 1);
  assert.equal((await status()).phase, "ready");
  assert.ok(requests.some(value => value.endsWith("/heartbeat")));
  assert.ok(requests.every(value => !value.includes(privateRecord.password)));
  assert.equal(await evaluate("location.hash + location.search"), "");
  assert.equal(await evaluate("localStorage.length + sessionStorage.length"), 0);

  const opened = sockets.length;
  const mark = events.length;
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "editor before disconnect");
  await send("Input.insertText", { text: "continued after reconnect" });
  await wait(editor, inputs => inputs[0]?.value === "continued after reconnect", "draft before transport loss");
  assert.equal(await evaluate("__kodexTestCloseTransport()"), 1);
  await wait(() => events.slice(mark), values => values.some(e => e.method === "Network.webSocketClosed"), "owned business socket closed");
  await wait(() => sockets.length, value => value > opened, "exact-instance replacement");
  await waitText("Backend: Connected");
  const recovered = await waitText("test answer — 你好");
  assert.equal(recovered.filter(node => node.role?.value === "StaticText" && node.name?.value === "abXcd").length, 1);
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active &&
    inputs[0].value === "continued after reconnect", "recovered editor retains its draft");
  await key("Enter", 13);
  await waitText("continued after reconnect");
  await wait(editor, inputs => inputs[0]?.value === "" && inputs[0]?.active, "continued write resolved");
  await wait(tree, nodes => nodes.filter(node => node.role?.value === "StaticText" &&
    node.name?.value === "test answer — 你好").length === 2, "second response completed");

  const disconnect = await position(await button("Disconnect"));
  await click("Settings");
  await click("Settings page OpenAi");
  const login = await button("Sign in");
  disabledAccessibility.push(login.properties.some(property => property.name === "disabled" && property.value.value === true));
  await waitText("Provider sign-in is unavailable in this host. Sign in using the Native client.");
  await pointer(await position(login));
  await click("Sign in");
  await delay(200);
  await waitText("Provider sign-in is unavailable in this host. Sign in using the Native client.");
  await click("Settings page ContextSources");
  await waitText("Context source path editing is unavailable in this host.");
  await click("Settings page CurrentSession");
  const browse = await button("Browse");
  disabledAccessibility.push(browse.properties.some(property => property.name === "disabled" && property.value.value === true));
  await pointer(await position(browse));
  await click("Browse");
  await delay(200);
  await button("Model: test-model");
  assert.ok(!text(await tree()).includes("A frontend operation failed."));
  assert.deepEqual(errors, []);
  await writeFile(join(output, "product-functional.png"), Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await key("Escape", 27);
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active && !inputs[0].disabled, "root returned after Settings");
  await send("Emulation.setDeviceMetricsOverride", { width: 900, height: 700, deviceScaleFactor: 1, mobile: false });
  await wait(async () => evaluate(`(() => {
    let result = null;
    function visit(root) {
      for (const element of root.querySelectorAll('*')) {
        if (element.tagName === 'TEXTAREA') {
          const rect = element.getBoundingClientRect();
          result = { left:rect.left, top:rect.top, right:rect.right, bottom:rect.bottom,
            width:rect.width, height:rect.height, viewportWidth:innerWidth, viewportHeight:innerHeight };
        }
        if (element.shadowRoot) visit(element.shadowRoot);
      }
    }
    visit(document); return result;
  })()`), rect => rect && rect.width > 0 && rect.height > 0 && rect.left >= 0 && rect.top >= 0 &&
    rect.right <= rect.viewportWidth && rect.bottom <= rect.viewportHeight, "compact desktop editor within viewport");
  await writeFile(join(output, "product-900x700.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  measurements.runtimeHeap = await send("Runtime.getHeapUsage");
  measurements.rendererMetrics = (await send("Performance.getMetrics")).metrics;
  measurements.scope = `Single isolated ${process.platform}/${process.arch} headless run, 900x700 and 1280x1000; offline fixture with 300ms response parts. Not a cold-machine, long-history or process-RSS benchmark.`;
  await writeFile(join(output, "performance-sample.json"), JSON.stringify(measurements, null, 2));
  await send("Emulation.clearDeviceMetricsOverride");
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "editor after viewport restoration");
  await pointer(disconnect);
  await wait(editor, inputs => inputs.length === 0, "disconnect unmounts application");
  await delay(500);
  const heartbeatCount = requests.filter(value => value.endsWith("/heartbeat")).length;
  await delay(21_000);
  assert.equal(requests.filter(value => value.endsWith("/heartbeat")).length, heartbeatCount,
    "Disconnect must cancel the frontend-owned renewal task.");
  assert.equal((await status()).phase, "ready", "Disconnect is not backend Stop.");
  const reloadRequests = requests.length;
  const reloadSockets = sockets.length;
  await send("Page.reload");
  await button("Choose private connection record");
  await delay(500);
  assert.equal(sockets.length, reloadSockets);
  assert.ok(requests.slice(reloadRequests).every(value => !value.endsWith("/heartbeat")));
  assert.equal(await evaluate("localStorage.length + sessionStorage.length"), 0);
  await writeFile(join(output, "product-result.json"), JSON.stringify({
    browser, sockets: sockets.length, fonts: [...fonts], errors,
    realProductionHost: true, exactInstanceRecovery: true, unavailableControlsDisabled: true,
    disconnectCancelsRenewalWithoutStoppingBackend: true, reloadRequiresExplicitImport: true,
    accessibilityDisabledMapping: disabledAccessibility.every(Boolean),
  }, null, 2));
  console.log("PASS independent Web entry functional checks: production auth/heartbeat/RPC, cursor, stream, recovery, capabilities and disconnect/reload");
  // Keep browser accessibility a separate strict gate, not a raster/functional substitute.
  assert.ok(disabledAccessibility.every(Boolean), "Browser accessibility must expose both disabled capability controls.");
} catch (failure) {
  await writeFile(join(output, "failure-events.json"), JSON.stringify({
    failure: failure.message,
    events: events.slice(-100).map(event => ({
      method: event.method,
      status: event.params?.response?.status,
      path: (() => {
        const url = event.params?.request?.url ?? event.params?.response?.url;
        try { return url ? new URL(url).pathname : undefined; } catch { return undefined; }
      })(),
    })),
  }, null, 2), { mode: 0o600 });
  await writeFile(join(output, "chrome.stderr.log"), chromeErrors, { mode: 0o600 });
  if (sessionId && socket?.readyState === WebSocket.OPEN) {
    try {
      await writeFile(join(output, "product-failure.png"),
        Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
      await writeFile(join(output, "product-failure.json"), JSON.stringify({
        errors, sockets: sockets.length, lastEvents: events.slice(-12).map(event => event.method),
        editors: await editor(), disabledAccessibility,
      }, null, 2));
    } catch { /* Browser may have exited before evidence capture. */ }
  }
  throw failure;
} finally {
  if (socket) socket.close();
  chrome.kill("SIGTERM");
  await Promise.race([exited, delay(5000)]);
  if (chrome.exitCode === null) { chrome.kill("SIGKILL"); await exited; }
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

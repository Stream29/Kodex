import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { mkdtemp, mkdir, readFile, rm, writeFile, stat } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { freshPairingOffer, probeCapabilities, secretMarkers } from "./check-capabilities.mjs";
import { sensitiveResponseScanner } from "./sensitive-response-scanner.mjs";

const require = createRequire(resolve(process.argv[2]));
const WebSocket = require("ws");
const output = resolve(process.argv[3]);
const record = process.env.KODEX_BROWSER_RECORD ?? "";
const capabilityCheck = process.env.KODEX_CHECK_CAPABILITIES === "1";
const automaticCheck = capabilityCheck && process.env.KODEX_CAPABILITY_ENTRY === "automatic";
const fixtureTemporary = capabilityCheck ? join(process.env.KODEX_CAPABILITY_SANDBOX, "tmp") : "/tmp";
assert.equal(dirname(dirname(dirname(dirname(record)))), fixtureTemporary);
assert.match(record.slice(fixtureTemporary.length), /^\/kodex-product-browser--?\d+\/\.locks\/backend\/connection\.json$/,
  "Only the task-owned production fixture's management record may be read by the Node controller.");
const privateRecord = JSON.parse(await readFile(record, "utf8"));
assert.equal((await stat(record)).mode & 0o777, 0o600, "The production host must protect its private connection record.");
assert.ok(Number.isInteger(privateRecord.port) && privateRecord.port > 0 && privateRecord.port <= 65535);
assert.equal(typeof privateRecord.password, "string");
assert.ok(privateRecord.password.length >= 32);
assert.equal(process.env.KODEX_CHECK_HISTORY, undefined, "History uses its own unchanged strict driver.");
const url = "http://127.0.0.1:18795/";
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(capabilityCheck ? process.env.KODEX_CAPABILITY_SANDBOX : output, "owned-chrome-"));
const chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
  "--headless=new", "--disable-dev-shm-usage", "--remote-debugging-address=127.0.0.1",
  "--disable-background-networking", "--disable-component-update", "--no-first-run", "--no-default-browser-check",
  "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--window-size=1280,1000",
  ...(process.platform === "darwin" ? ["--password-store=basic", "--use-mock-keychain"] : []),
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
const businessSocketIds = new Set();
const requests = [];
const fonts = new Set();
const enabledAccessibility = [];
const measurements = {};
const capabilityMethods = [
  "listDirectories", "validateDirectory", "evaluateContextPaths", "startOAuthLogin", "awaitOAuthLogin",
  "forcedCompact", "reportFrontendError",
];
const rpcEvidence = Object.fromEntries(capabilityMethods.map(name => [name, 0]));
const hostEvidence = {};
let capturedLaunchBase = false;
const sensitiveRequests = new Map();
const responseScanner = sensitiveResponseScanner(send, scanSensitive, safeNetworkPath);
let sensitiveBodyResponses = 0;
let secretLeak = false;
let sessionId;
function scanSensitive(value, existingBusinessBaseline = false) {
  if (typeof value !== "string") value = JSON.stringify(value);
  const forbidden = existingBusinessBaseline ? secretMarkers.slice(0, 3) : secretMarkers;
  if ([privateRecord.password, ...forbidden].some(secret => value.includes(secret))) secretLeak = true;
  if (existingBusinessBaseline && secretMarkers.slice(3).some(secret => value.includes(secret))) {
    measurements.existingMcpSettingsSecretFrames = (measurements.existingMcpSettingsSecretFrames ?? 0) + 1;
  }
}
function safeNetworkPath(value) {
  try {
    const path = new URL(value).pathname;
    return path.startsWith("/web/") ? "/web/[private]/[asset-or-bootstrap]" :
      ["/frontend-host", "/frontend-pair", "/browser/rpc", "/browser/heartbeat"].includes(path) ? path : "[asset]";
  } catch { return "[unparsed]"; }
}
function isSensitiveHttp(value) {
  try {
    const path = new URL(value).pathname;
    return path.endsWith("/bootstrap") || ["/frontend-host", "/frontend-pair"].includes(path);
  } catch { return false; }
}
function readRpcEvidence(value, depth = 0) {
  if (depth > 16 || value == null) return;
  if (typeof value === "string") {
    if (/^[{[]/.test(value)) {
      try { readRpcEvidence(JSON.parse(value), depth + 1); } catch {}
    }
  } else if (Array.isArray(value)) {
    for (const item of value) readRpcEvidence(item, depth + 1);
  } else if (typeof value === "object") {
    if (value.launchBaseDirectory === process.env.KODEX_CAPABILITY_LAUNCH_DIRECTORY) capturedLaunchBase = true;
    for (const [key, item] of Object.entries(value)) {
      if (key === "callableName" || key === "method") {
        for (const name of capabilityMethods) if (typeof item === "string" && item.endsWith(name)) rpcEvidence[name]++;
      }
      readRpcEvidence(item, depth + 1);
    }
  }
}

async function send(method, params = {}) {
  if (method === "Page.navigate" || method === "Page.reload") await responseScanner.drain();
  if (method === "Page.captureScreenshot") {
    scanSensitive((await send("Accessibility.getFullAXTree")).nodes);
    assert.equal(secretLeak, false, "Do not archive a page that exposed a sensitive marker.");
  }
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
  return { role: node.role?.value, ignored: node.ignored === true,
    disabled: node.properties?.some(property => property.name === "disabled" && property.value.value === true) === true,
    matchingNodes: nodes.filter(value => value.name?.value === name).map(value => ({
      role: value.role?.value, ignored: value.ignored === true,
      backendNodeId: value.backendDOMNodeId,
    })) };
}
async function editor() {
  return evaluate(`(() => {
    const found = [];
    function visit(root) {
      for (const e of root.querySelectorAll('*')) {
        if (e.tagName === 'TEXTAREA') {
          const rect = e.getBoundingClientRect();
          found.push({ value: e.value, cursor: e.selectionEnd, label: e.getAttribute('aria-label'),
            active: e.getRootNode().activeElement === e, disabled: e.disabled,
            bounds: { left:rect.left, top:rect.top, right:rect.right, bottom:rect.bottom, height:rect.height },
            clientHeight:e.clientHeight, scrollHeight:e.scrollHeight });
        }
        if (e.shadowRoot) visit(e.shadowRoot);
      }
    }
    visit(document); return found;
  })()`);
}
async function checkAdaptiveComposer() {
  await evaluate(`(() => {
    function find(root) {
      for (const element of root.querySelectorAll('*')) {
        if (element.tagName === 'TEXTAREA' && element.getAttribute('aria-label') === 'Message') return element;
        if (element.shadowRoot) { const found = find(element.shadowRoot); if (found) return found; }
      }
      return null;
    }
    globalThis.__kodexGeometryInput = find(document);
  })()`);
  const sample = () => evaluate(`(() => {
    const input = globalThis.__kodexGeometryInput;
    if (!input?.isConnected) return null;
    const style = getComputedStyle(input);
    return {
      width: input.getBoundingClientRect().width, height: input.getBoundingClientRect().height,
      lineHeight: parseFloat(style.lineHeight),
      clientHeight: input.clientHeight, scrollHeight: input.scrollHeight,
      cursor: input.selectionEnd, value: input.value,
      active: input.getRootNode().activeElement === input,
    };
  })()`);
  const replace = async value => {
    await evaluate(`(() => {
      const input = globalThis.__kodexGeometryInput;
      if (!input?.isConnected) throw new Error('Composer DOM node was replaced');
      input.value = ${JSON.stringify(value)};
      input.setSelectionRange(input.value.length, input.value.length);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    })()`);
  };
  async function stable(matches, label) {
    let previous;
    let count = 0;
    return wait(sample, value => {
      if (!value || !matches(value)) { count = 0; return false; }
      const identity = JSON.stringify([value.height, value.clientHeight, value.scrollHeight, value.value]);
      count = identity === previous ? count + 1 : 1;
      previous = identity;
      return count >= 3;
    }, label);
  }
  try {
    const empty = await stable(value => value.value === "" && value.active &&
      Math.abs(value.height - value.lineHeight) <= 1, "empty Composer is one visual line");
    await replace("one line");
    await stable(value => value.value === "one line" && Math.abs(value.height - empty.height) <= 1,
      "single-line Composer retains one line");
    await replace("line one\nline two");
    const two = await stable(value => value.value === "line one\nline two" &&
      Math.abs(value.height - 2 * value.lineHeight) <= 1, "Composer grows for two lines");
    await replace(Array.from({ length: 6 }, (_, index) => `line ${index}`).join("\n"));
    const six = await stable(value => Math.abs(value.height - 6 * value.lineHeight) <= 1,
      "Composer grows to six lines");
    const sevenLines = Array.from({ length: 7 }, (_, index) => `line ${index}`).join("\n");
    await replace(sevenLines);
    const seven = await stable(value => value.value === sevenLines &&
      Math.abs(value.height - six.height) <= 1 && value.scrollHeight > value.clientHeight,
      "seventh line scrolls within the six-line Composer");
    assert.equal(await evaluate(`(() => {
      const input = globalThis.__kodexGeometryInput;
      input.scrollTop = input.scrollHeight;
      return input.scrollTop > 0 && input.selectionEnd === input.value.length;
    })()`), true, "The overflow and active cursor remain reachable in the same DOM node.");
    await replace("short");
    await stable(value => value.value === "short" && Math.abs(value.height - empty.height) <= 1 && value.active,
      "Composer shrinks without losing focus");
    const wrappedText = "wrap ".repeat(42);
    await replace(wrappedText);
    const wide = await stable(value => value.value === wrappedText && value.height > value.lineHeight,
      "Composer measures soft wrapping");
    try {
      await send("Emulation.setDeviceMetricsOverride", {
        width: 640, height: 700, deviceScaleFactor: 1, mobile: false,
      });
      await stable(value => value.width < wide.width && value.scrollHeight > wide.scrollHeight &&
        value.height >= wide.height && value.height <= 6 * value.lineHeight + 1,
        "narrower Composer remeasures soft wraps without exceeding six lines");
    } finally {
      await send("Emulation.clearDeviceMetricsOverride");
    }
    await stable(value => Math.abs(value.width - wide.width) <= 1 &&
      Math.abs(value.height - wide.height) <= 1, "wider Composer shrinks after viewport restoration");
    await replace("");
    await stable(value => value.value === "" && Math.abs(value.height - empty.height) <= 1 && value.active,
      "Composer returns to an empty single line");
    const metricValues = async () => Object.fromEntries(
      (await send("Performance.getMetrics")).metrics.map(metric => [metric.name, metric.value]));
    const beforeIdle = await metricValues();
    await delay(1000);
    const afterIdle = await metricValues();
    const idleLayouts = afterIdle.LayoutCount - beforeIdle.LayoutCount;
    assert.ok(idleLayouts <= 4, `Idle Composer must not continuously remeasure its DOM mirror (${idleLayouts} layouts).`);
    measurements.adaptiveComposer = {
      emptyHeight: empty.height, twoLineHeight: two.height, sixLineHeight: six.height,
      sevenLineHeight: seven.height, overflowHeight: seven.scrollHeight,
      sameDomNode: true,
      idleLayouts,
    };
  } finally {
    await evaluate("delete globalThis.__kodexGeometryInput");
  }
}
async function key(name, code, modifiers = 0) {
  for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", {
    type, key: name, code: name, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, modifiers,
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
async function controlBox(node) {
  if (!node?.backendDOMNodeId) return null;
  const result = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }).catch(() => null);
  if (!result) return null;
  const q = result.model.content;
  const x = Math.min(q[0], q[2], q[4], q[6]), y = Math.min(q[1], q[3], q[5], q[7]);
  const width = Math.max(q[0], q[2], q[4], q[6]) - x;
  const height = Math.max(q[1], q[3], q[5], q[7]) - y;
  return width > 0 && height > 0 ? { x, y, width, height } : null;
}
async function selectedTabBox() {
  return controlBox((await tree()).find(node => node.role?.value === "tab" &&
    node.name?.value?.includes("Session tab:") &&
    node.properties?.some(property => property.name === "selected" && property.value.value === true)));
}
async function catalogRowBox() {
  const nodes = await tree();
  const action = nodes.find(node => /^Open menu for session \d+$/.test(node.name?.value ?? ""));
  const index = action?.name.value.match(/\d+$/)?.[0];
  if (index === undefined) return null;
  // This deterministic fixture disables automatic naming and has one persisted Session.
  return controlBox(nodes.find(node => node.role?.value === "button" && node.name?.value === `Session ${index}`));
}
async function captureSpinnerControl(box) {
  return (await send("Page.captureScreenshot", {
    format: "png", clip: { ...box, scale: 2 },
  })).data;
}
async function assertSpinnerRasterChanges(box, name) {
  // Let initial control placement/color settle; the sampled title/timestamp are static.
  // Capture only this control, never a whole page whose other animations could mask failure.
  await delay(300);
  const first = await captureSpinnerControl(box);
  let next = first;
  for (let attempt = 0; attempt < 3 && next === first; attempt++) {
    await delay(150);
    next = await captureSpinnerControl(box);
  }
  assert.notEqual(next, first, `${name} must actually animate, not merely expose Running semantics.`);
  await writeFile(join(output, `${name}-frame-1.png`), Buffer.from(first, "base64"));
  await writeFile(join(output, `${name}-frame-2.png`), Buffer.from(next, "base64"));
}
async function assertStoppedRasterStable(box, name) {
  await delay(300);
  const first = await captureSpinnerControl(box);
  for (const interval of [150, 250]) {
    await delay(interval);
    assert.equal(await captureSpinnerControl(box), first, `${name} must stop animating.`);
  }
  await writeFile(join(output, `${name}.png`), Buffer.from(first, "base64"));
}
async function checkRunningSpinners() {
  assert.equal(process.env.KODEX_BROWSER_FIXTURE_DELAY_MS, "5000",
    "The spinner scenario requires the owned fixture's bounded long-running response.");
  await send("Page.bringToFront");
  assert.equal(await evaluate("document.visibilityState"), "visible");
  const initial = await wait(tree, nodes => nodes.some(node => node.name?.value?.includes("Session tab:")),
    "persisted running tab mounted");
  await writeFile(join(output, "spinner-running-ax.json"), JSON.stringify(initial.filter(node =>
    node.name?.value === "Running" || node.name?.value?.includes("Session tab:")).map(node => ({
      role: node.role?.value, name: node.name?.value, ignored: node.ignored, properties: node.properties,
    })), null, 2));
  const tab = await wait(selectedTabBox, box => box && box.y < 80,
    "production tab running spinner");
  await assertSpinnerRasterChanges(tab, "spinner-tab");
  await click("Sessions");
  await waitText("Show archived");
  const catalog = await wait(catalogRowBox, box => box && box.y > 100,
    "production catalog running spinner");
  await assertSpinnerRasterChanges(catalog, "spinner-catalog");
  await writeFile(join(output, "spinner-catalog-running.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await key("Escape", 27);
  await wait(tree, nodes => !text(nodes).includes("Show archived"), "running Catalog dismissed");
  measurements.runningSpinners = { tabRasterChanges: true, catalogRasterChanges: true };
}
async function checkStoppedSpinners() {
  const tab = await wait(selectedTabBox, Boolean, "stopped selected tab geometry");
  await assertStoppedRasterStable(tab, "spinner-tab-idle");
  await click("Sessions");
  await waitText("Show archived");
  await wait(tree, nodes => text(nodes).includes("Open menu for session") && !text(nodes).includes("Loading sessions"),
    "stopped catalog snapshot loaded");
  const catalog = await wait(catalogRowBox, Boolean, "stopped catalog row geometry");
  await assertStoppedRasterStable(catalog, "spinner-catalog-row-idle");
  await writeFile(join(output, "spinner-catalog-idle.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  const catalogNodes = await tree();
  assert.ok(!catalogNodes.some(node => /^Actions for session \d+$/.test(node.name?.value ?? "")),
    "The old painted row Actions control must be absent.");
  const menuActions = catalogNodes.filter(node => node.role?.value === "button" &&
    /^Open menu for session \d+$/.test(node.name?.value ?? ""));
  assert.equal(menuActions.length, 1, "The exact fixture row exposes one semantic menu action.");
  await click(menuActions[0].name.value);
  await button("Archive");
  await key("Escape", 27);
  await wait(tree, nodes => !nodes.some(node => node.name?.value === "Archive"), "semantic menu dismissed");
  await key("F10", 121, 8);
  await button("Archive");
  await key("Escape", 27);
  await wait(tree, nodes => !nodes.some(node => node.name?.value === "Archive"), "keyboard menu dismissed");
  await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 1 });
  try {
    const point = { x: catalog.x + catalog.width / 2, y: catalog.y + catalog.height / 2 };
    await send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [point] });
    await send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    await wait(tree, nodes => !text(nodes).includes("Show archived"), "synthetic touch opens the catalog row");
  } finally {
    await send("Emulation.setTouchEmulationEnabled", { enabled: false });
  }
  await click("Sessions");
  await waitText("Show archived");
  await key("Escape", 27);
  await wait(tree, nodes => !text(nodes).includes("Show archived"), "idle Catalog dismissed");
  measurements.runningSpinners.stoppedTabAndCatalogRasterStable = true;
  measurements.runningSpinners.geometrySource = "Actual tab/catalog controls; no independent child-AX assumption.";
  measurements.catalogRowMenu = {
    semanticAction: true, focusRestoredForShiftF10: true, paintedActionsAbsent: true,
    syntheticTouchKeepsPrimaryOpen: true,
  };
}
async function clickVisibleButton(name) {
  let previous = "", stable = 0;
  const point = await wait(async () => {
    const nodes = await tree();
    const node = nodes.find(node => node.role?.value === "button" && node.name?.value === name && !node.ignored);
    return node ? position(node).catch(() => null) : null;
  }, point => {
    if (!point || point.x <= 0 || point.y <= 0) return false;
    const value = JSON.stringify(point);
    stable = value === previous ? stable + 1 : 1;
    previous = value;
    return stable >= 3;
  }, `stable ${name} geometry`);
  await pointer(point);
}
async function checkRuntimeCascade(label) {
  const trigger = (await tree()).find(node => node.role?.value === "button" &&
    node.name?.value?.startsWith("Runtime model: "));
  assert.ok(trigger?.backendDOMNodeId, "The runtime selector must be present.");
  async function columns(count) {
    const result = await wait(tree, nodes =>
      nodes.filter(node => node.name?.value === "Runtime model menu").length === count,
    `${count} simultaneous runtime menu columns`);
    await delay(100);
    return result;
  }
  async function choose(name) {
    const nodes = await wait(tree, values => values.some(node =>
      node.name?.value === name && node.backendDOMNodeId), name);
    await pointer(await position(nodes.find(node => node.name?.value === name && node.backendDOMNodeId)));
  }
  await pointer(await position(trigger));
  await columns(1);
  await choose("Runtime model: test-model");
  await columns(2);
  await choose("Runtime model: medium");
  const opened = await columns(3);
  assert.ok(opened.some(node => node.name?.value === "Runtime model: test-model"),
    "Opening an effort must retain the model column.");
  await writeFile(join(output, `runtime-cascade-${label}.png`),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await choose("Runtime model: default");
  await columns(0);
  await wait(editor, inputs => inputs.length === 1 && !inputs[0].disabled,
    "Runtime selection releases the DOM editor overlay");
  await pointer(await position(await button(trigger.name.value)));
  await columns(1);
  await key("ArrowRight", 39);
  await columns(2);
  await key("ArrowRight", 39);
  await columns(3);
  for (const remaining of [2, 1, 0]) {
    await key("Escape", 27);
    await columns(remaining);
  }
  await key("Enter", 13);
  await columns(1);
  await key("Escape", 27);
  await columns(0);
  measurements[`runtimeCascade-${label}`] = { parentColumnsRetained: true, layeredKeyboardReturn: true };
  await evaluate(`(() => {
    function visit(root) {
      for (const element of root.querySelectorAll('*')) {
        if (element.tagName === 'TEXTAREA' && element.getAttribute('aria-label') === 'Message') {
          element.focus(); return true;
        }
        if (element.shadowRoot && visit(element.shadowRoot)) return true;
      }
      return false;
    }
    if (!visit(document)) throw new Error('Composer missing after runtime menu dismissal');
  })()`);
}
async function importRecord() {
  const offer = capabilityCheck ? await freshPairingOffer() : process.env.KODEX_BROWSER_PAIRING_FILE;
  assert.equal(offer, record.replace(".locks/backend/connection.json", "frontend-pairing.private"),
    "Only the task fixture's scoped pairing file may enter Wasm.");
  assert.equal((await stat(offer)).mode & 0o777, 0o600);
  assert.ok(!((await readFile(offer, "utf8")).includes(privateRecord.password)));
  await selectRecord(offer);
  await waitText("Backend: Connected");
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "shared root mounted");
}
async function selectRecord(file) {
  await send("Page.setInterceptFileChooserDialog", { enabled: true });
  const mark = events.length;
  await click("Choose scoped pairing record");
  const event = await wait(() => events.slice(mark).find(e => e.method === "Page.fileChooserOpened"), Boolean, "file chooser");
  await send("DOM.setFileInputFiles", { backendNodeId: event.params.backendDOMNodeId ?? event.params.backendNodeId, files: [file] });
}
async function checkConnectionEntry() {
  await button("Choose scoped pairing record");
  await send("Emulation.setDeviceMetricsOverride", { width: 320, height: 240, deviceScaleFactor: 1, mobile: false });
  await waitText("Trust this Web package");
  const invalid = join(profile, "invalid-record.json");
  const oversized = join(profile, "oversized-record.json");
  await writeFile(invalid, "{invalid", { mode: 0o600 });
  await writeFile(oversized, " ".repeat(65537), { mode: 0o600 });
  for (const file of [invalid, oversized]) {
    const before = sockets.length;
    await selectRecord(file);
    await waitText("Could not read a valid scoped pairing record.");
    assert.equal(sockets.length, before, "Rejected records must not start transport.");
    await click("Dismiss message");
    await wait(tree, nodes => !text(nodes).includes("Could not read a valid scoped pairing record."), "message dismissal");
  }
  await send("Page.setInterceptFileChooserDialog", { enabled: true });
  const mark = events.length;
  await click("Choose scoped pairing record");
  await wait(() => events.slice(mark).find(e => e.method === "Page.fileChooserOpened"), Boolean, "cancelled chooser");
  // Exercise the real DOM cancellation handler; this is not a native OS-picker test.
  await evaluate("document.querySelector('input[type=file]').dispatchEvent(new Event('cancel'))");
  await wait(tree, nodes => nodes.some(node => node.role?.value === "button" &&
    node.name?.value === "Choose scoped pairing record" &&
    !node.properties?.some(p => p.name === "disabled" && p.value.value)), "chooser enabled after cancellation");
  assert.ok(!text(await tree()).includes("Could not read a valid scoped pairing record."));
  await writeFile(join(output, "connection-320x240.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await send("Emulation.clearDeviceMetricsOverride");
  measurements.connectionEntry = { malformedRejected: true, oversizedRejected: true, domCancelReenables: true };
}
async function checkAutomaticEntry() {
  const launchFile = process.env.KODEX_BROWSER_LAUNCH_FILE;
  if (!launchFile) return;
  assert.equal(launchFile, record.replace(".locks/backend/connection.json", "frontend-launch.private"));
  assert.equal((await stat(launchFile)).mode & 0o777, 0o600);
  const privateUrl = await readFile(launchFile, "utf8");
  assert.ok(/^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/.test(privateUrl),
    "The task-owned launch handoff must have the production format.");
  await send("Page.navigate", { url: privateUrl });
  await waitText("Backend: Connected");
  await wait(editor, inputs => inputs.some(input => input.label === "Message" && input.active), "automatic shared root");
  assert.equal(await evaluate("location.hash"), "", "Bootstrap removes the private fragment.");
  await send("Page.reload");
  await waitText("Backend: Connected");
  await wait(editor, inputs => inputs.some(input => input.label === "Message" && input.active), "automatic reload root");
  await click("Disconnect");
  await waitText("Local Kodex session disconnected");
  assert.ok(!text(await tree()).includes("Choose scoped pairing record"));
  await writeFile(join(output, "automatic-disconnected.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  measurements.automaticEntry = { productionBootstrap: true, reload: true, disconnectedBranch: true };
}
async function replaceNamedEditor(label, value) {
  await wait(editor, inputs => inputs.some(input => input.label === label && input.active), `focused ${label}`);
  await evaluate(`(() => {
    function visit(root) {
      for (const element of root.querySelectorAll('*')) {
        if (element.tagName === 'TEXTAREA' && element.getAttribute('aria-label') === ${JSON.stringify(label)}) {
          element.value = ${JSON.stringify(value)};
          element.setSelectionRange(element.value.length, element.value.length);
          element.dispatchEvent(new Event('input', { bubbles:true }));
          return true;
        }
        if (element.shadowRoot && visit(element.shadowRoot)) return true;
      }
      return false;
    }
    if (!visit(document)) throw new Error('Expected owned editor was not mounted');
  })()`);
  await wait(editor, inputs => inputs.some(input => input.label === label && input.value === value), `edited ${label}`);
}
async function openSelectedTabMenu(method = "keyboard") {
  let previous = "";
  let stable = 0;
  const point = await wait(async () => {
    const tab = (await tree()).find(node => node.role?.value === "tab" &&
      node.name?.value?.includes("Session tab:") &&
      node.properties?.some(property => property.name === "selected" && property.value.value === true));
    return tab ? position(tab).catch(() => null) : null;
  }, point => {
    if (!point || point.x <= 0 || point.y <= 0) return false;
    const value = JSON.stringify(point);
    stable = value === previous ? stable + 1 : 1;
    previous = value;
    return stable >= 3;
  }, "stable selected tab geometry");
  if (method === "keyboard") {
    await pointer(point);
    await key("F10", 121, 8);
  } else {
    await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
    for (const type of ["mousePressed", "mouseReleased"]) {
      await send("Input.dispatchMouseEvent", { type, ...point, button: "right", clickCount: 1 });
    }
  }
  await button("Application menu action: rename");
}
async function checkRootEntries() {
  await send("Emulation.setDeviceMetricsOverride", { width: 320, height: 240, deviceScaleFactor: 1, mobile: false });
  for (const [page, expected] of [
    ["General", "Input"], ["NewSession", "Automatic session title"],
    ["OpenAi", "OpenAI account"], ["ContextSources", "Custom sources"],
    ["Mcp", "MCP servers"], ["Hooks", "Hooks"], ["CurrentSession", "Identity"],
  ]) {
    const name = `Settings page ${page}`;
    const nodes = await wait(tree, nodes => nodes.some(node => node.name?.value === name), name);
    const pages = ["General", "ContextSources", "OpenAi", "Mcp", "Hooks", "CurrentSession", "NewSession"];
    const current = nodes.find(node => node.name?.value?.startsWith("Settings page ") &&
      node.properties?.some(property => property.name === "selected" && property.value.value));
    const direction = pages.indexOf(page) < pages.indexOf(current?.name.value.slice("Settings page ".length)) ? -1 : 1;
    let point;
    for (let attempt = 0; attempt < 12; attempt++) {
      const currentNodes = await tree();
      const target = currentNodes.find(node => node.name?.value === name && !node.ignored);
      point = target ? await position(target).catch(() => ({ x: 0, y: 0 })) : { x: 0, y: 0 };
      if (point.x > 4 && point.x < 316 && point.y > 0 && point.y < 240) break;
      await send("Input.dispatchMouseEvent", { type: "mouseWheel", x: 160, y: 80,
        deltaX: direction * 160, deltaY: 0 });
      await delay(100);
    }
    assert.ok(point.x > 4 && point.x < 316, `Settings ${page} navigation must be scroll reachable.`);
    await pointer(point);
    await waitText(`Settings content ${page}`);
    for (let attempt = 0; attempt < 12 && !text(await tree()).includes(expected); attempt++) {
      await key("PageDown", 34);
      await delay(75);
    }
    assert.ok(text(await tree()).includes(expected), `Settings ${page} content must be keyboard reachable.`);
    const close = await position(await button("Close"));
    assert.ok(close.y >= 0 && close.y <= 240, "Settings Close must remain inside the short viewport.");
    if (page === "Mcp" || page === "Hooks") {
      await click("Add");
      await waitText(page === "Mcp" ? "Add MCP server" : "Add Hook");
      await key("Escape", 27);
      await wait(tree, nodes => !text(nodes).includes(page === "Mcp" ? "Add MCP server" : "Add Hook"), "child editor dismissed");
    }
  }
  await click("Rename");
  await replaceNamedEditor("Session name", "UI settings — 完整 Session");
  await key("Enter", 13);
  await waitText("UI settings — 完整 Session");
  await wait(editor, inputs => !inputs.some(input => input.label === "Session name"), "Settings Rename submitted");
  await click("Rename");
  await replaceNamedEditor("Session name", "Unsaved cancellation draft");
  await key("Escape", 27);
  await wait(editor, inputs => !inputs.some(input => input.label === "Session name"), "Settings Rename cancelled");
  await waitText("UI settings — 完整 Session");
  await writeFile(join(output, "settings-320x240.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await send("Emulation.clearDeviceMetricsOverride");
  await key("Escape", 27);
  await wait(editor, inputs => inputs.some(input => input.label === "Message" && input.active), "root after Settings");
  await openSelectedTabMenu();
  await clickVisibleButton("Application menu action: rename");
  await replaceNamedEditor("Session name", "UI tab — 第二入口");
  await key("Enter", 13);
  await wait(editor, inputs => !inputs.some(input => input.label === "Session name"), "tab Rename submitted");
  await waitText("UI tab — 第二入口");
  await openSelectedTabMenu("mouse");
  await clickVisibleButton("Application menu action: delete");
  await waitText("This removes the persisted session.");
  await button("Cancel");
  await delay(300);
  measurements.rootDeleteAxFocused = (await tree()).some(node => node.role?.value === "button" &&
    node.name?.value === "Cancel" && node.properties?.some(property => property.name === "focused" && property.value.value));
  // Do not click/focus Cancel to manufacture the default. Enter must cancel the real popup.
  await key("Enter", 13);
  await wait(editor, inputs => inputs.some(input => input.label === "Message" && input.active), "Delete cancelled without deleting");
  await waitText("UI tab — 第二入口");
  await click("Sessions");
  await waitText("Show archived");
  await waitText("UI tab — 第二入口");
  await key("Escape", 27);
  await wait(tree, nodes => !text(nodes).includes("Show archived"), "Catalog dismissed");
  await wait(editor, inputs => inputs.some(input => input.label === "Message" && input.active), "root after Catalog");
  measurements.rootEntries = { settingsRename: true, renameCancel: true, tabRename: true, rootDeleteSafeCancel: true, catalog: true };
}
async function checkNarrowAgentEditor() {
  await send("Emulation.setDeviceMetricsOverride", { width: 320, height: 240, deviceScaleFactor: 1, mobile: false });
  const value = Array.from({ length: 10 }, (_, index) => `line ${index + 1}`).join("\n");
  await replaceNamedEditor("Message", value);
  const result = await wait(editor, inputs => inputs.some(input => input.label === "Message" &&
    input.value === value && input.active && input.bounds.height > 0 && input.bounds.left >= 0 &&
    input.bounds.top >= 0 && input.bounds.right <= 320 && input.bounds.bottom <= 240 &&
    input.scrollHeight > input.clientHeight), "narrow Agent six-line editor visible and internally scrollable");
  measurements.narrowAgentEditor = result.find(input => input.label === "Message");
  await writeFile(join(output, "agent-editor-320x240.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await replaceNamedEditor("Message", "");
  await send("Emulation.clearDeviceMetricsOverride");
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
      if (message.method === "Network.webSocketCreated") {
        sockets.push(message.params.url);
        const url = new URL(message.params.url);
        if (url.protocol === "ws:" && url.hostname === "127.0.0.1" &&
            url.port === String(privateRecord.port) && url.pathname === "/browser/rpc") {
          businessSocketIds.add(message.params.requestId);
        }
      }
      if (message.method === "Network.requestWillBeSent") requests.push(message.params.request.url);
      if (message.method === "Network.requestWillBeSent" && isSensitiveHttp(message.params.request.url)) {
        sensitiveRequests.set(message.params.requestId, { method: message.params.request.method });
      }
      if (message.method === "Network.responseReceived" && isSensitiveHttp(message.params.response.url)) {
        const preflight = message.params.type === "Preflight" ||
          sensitiveRequests.get(message.params.requestId)?.method === "OPTIONS";
        if (!(preflight && message.params.response.status === 204)) sensitiveBodyResponses++;
      }
      if (message.method === "Network.loadingFinished" || message.method === "Network.loadingFailed") {
        sensitiveRequests.delete(message.params.requestId);
      }
      if (message.method.startsWith("Network.") || message.method === "Runtime.consoleAPICalled") {
        const frame = ["Network.webSocketFrameSent", "Network.webSocketFrameReceived"].includes(message.method);
        // BackendSettings already transports embedded MCP credentials for the trusted CAS adapter.
        // They remain forbidden in the new host/pairing bridge, DOM and console, like all tokens.
        scanSensitive(message.params, frame && businessSocketIds.has(message.params.requestId));
      }
      if (message.method === "Network.webSocketFrameSent") readRpcEvidence(message.params.response?.payloadData);
      if (message.method === "Network.requestWillBeSent" && message.params.request.url.endsWith("/frontend-host")) {
        try {
          const request = JSON.parse(message.params.request.postData).request;
          const type = request.type;
          if (["reserve_callback", "bind_callback", "await_callback", "open_registered_authorization",
            "close_callback", "observe_error", "renew_owner", "close_owner"].includes(type)) {
            hostEvidence[type] = (hostEvidence[type] ?? 0) + 1;
          }
        } catch {}
      }
      if (message.method === "Fetch.requestPaused") responseScanner.observe(message.params);
      if (message.method === "Network.responseReceived" && message.params.response.status === 200 &&
          message.params.response.url.includes("/font/")) fonts.add(message.params.response.url.split("/").at(-1));
    }
  });
  await new Promise((done, fail) => { socket.once("open", done); socket.once("error", fail); });
  const browser = (await send("Browser.getVersion")).product;
  const target = await send("Target.createTarget", { url: "about:blank" });
  sessionId = (await send("Target.attachToTarget", { targetId: target.targetId, flatten: true })).sessionId;
  for (const domain of ["Runtime", "Network", "Accessibility", "Page", "Performance"]) await send(`${domain}.enable`);
  await send("Network.enable", { maxPostDataSize: 65_536 });
  await send("Fetch.enable", { patterns: [
    "http://127.0.0.1:*/web/*/bootstrap", "http://127.0.0.1:*/frontend-host", "http://127.0.0.1:*/frontend-pair",
  ].map(urlPattern => ({ urlPattern, requestStage: "Response" })) });
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
  await checkAutomaticEntry();
  if (process.env.KODEX_INVALID_ORIGIN_URL) {
    assert.equal(process.env.KODEX_INVALID_ORIGIN_URL, "http://[::1]:18795/");
    await send("Page.navigate", { url: process.env.KODEX_INVALID_ORIGIN_URL });
    await waitText("Serve this package from an HTTP loopback origin");
    assert.ok(!text(await tree()).includes("Choose scoped pairing record"));
    measurements.invalidOriginRejected = true;
  }
  const navigationStarted = Date.now();
  if (automaticCheck) {
    assert.ok(process.env.KODEX_BROWSER_LAUNCH_FILE, "Automatic production entry is required.");
    // checkAutomaticEntry disconnected deliberately; reload uses only its scoped tab capability.
    await send("Page.reload");
    await waitText("Backend: Connected");
    await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "automatic root restored");
  } else {
    await send("Page.navigate", { url });
    await checkConnectionEntry();
    await importRecord();
    if (capabilityCheck) {
      const beforeReload = sockets.length;
      await send("Page.reload");
      await button("Choose scoped pairing record");
      await delay(500);
      assert.equal(sockets.length, beforeReload, "Manual reload must not reuse a consumed pairing ticket.");
      assert.equal(await evaluate("localStorage.length + sessionStorage.length"), 0);
      await importRecord();
      measurements.manualEntry = { reloadRequiresExplicitPairing: true, freshOfferReconnectsRealSharedRoot: true };
    }
  }
  await checkRuntimeCascade("new-session");
  await replaceNamedEditor("Message", "paired draft 你好");
  measurements.pairedNewSessionDraft = (await editor()).find(input => input.label === "Message");
  await writeFile(join(output, "paired-new-session-1280x1000.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await replaceNamedEditor("Message", "");
  measurements.navigationToSharedRootIncludingExplicitImportMilliseconds = Date.now() - navigationStarted;
  assert.ok(sockets.every(value => value === `ws://127.0.0.1:${privateRecord.port}/browser/rpc`));
  assert.ok(fonts.has("noto_sans_sc.ttf") && fonts.has("noto_sans_math.ttf"));
  await send("Input.insertText", { text: "abcd" });
  await key("ArrowLeft", 37); await key("ArrowLeft", 37);
  await send("Input.insertText", { text: "X" });
  await wait(editor, inputs => inputs[0]?.value === "abXcd" && inputs[0]?.cursor === 3, "strict cursor");
  const responseStarted = Date.now();
  await key("Enter", 13);
  if (process.env.KODEX_CHECK_RUNNING_SPINNERS === "1") await checkRunningSpinners();
  const answer = await waitText("test answer — 你好");
  if (process.env.KODEX_CHECK_RUNNING_SPINNERS === "1") await checkStoppedSpinners();
  measurements.submitToCompletedFixtureResponseMilliseconds = Date.now() - responseStarted;
  assert.equal(answer.filter(node => node.role?.value === "StaticText" && node.name?.value === "abXcd").length, 1);
  assert.equal((await status()).phase, "ready");
  assert.ok(requests.some(value => value.endsWith("/heartbeat")));
  assert.ok(requests.every(value => !value.includes(privateRecord.password)));
  assert.equal(await evaluate("location.hash + location.search"), "");
  assert.equal(await evaluate("localStorage.length"), 0);
  assert.equal(await evaluate("sessionStorage.length"), automaticCheck ? 1 : 0);

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
  await checkAdaptiveComposer();
  await checkRuntimeCascade("agent");

  const disconnect = await position(await button("Disconnect"));
  if (capabilityCheck) {
    await probeCapabilities({
      click, button, tree, text, wait, waitText, key, editor, evaluate, send, measurements,
    });
    await click("Settings");
  } else {
    await click("Settings");
    await click("Settings page OpenAi");
    const login = await button("Sign in");
    enabledAccessibility.push(!login.properties?.some(p => p.name === "disabled" && p.value.value === true));
    await click("Sign in");
    await button("Open browser");
    await key("Escape", 27);
    await click("Settings page ContextSources");
    await click("Add source");
    await waitText("Add context source");
    await key("Escape", 27);
    await click("Settings page CurrentSession");
    const browse = await button("Browse");
    enabledAccessibility.push(!browse.properties?.some(p => p.name === "disabled" && p.value.value === true));
    await click("Browse");
    await button("Select");
    await click("Cancel");
  }
  await click("Settings page CurrentSession");
  await button("Model: test-model");
  assert.ok(!text(await tree()).includes("A frontend operation failed."));
  assert.deepEqual(errors, []);
  await writeFile(join(output, "product-functional.png"), Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await checkRootEntries();
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active && !inputs[0].disabled, "root returned after Settings");
  await checkNarrowAgentEditor();
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
  measurements.scope = `Single isolated ${process.platform}/${process.arch} headless run, 320x240, 900x700 and 1280x1000; offline fixture with ${process.env.KODEX_BROWSER_FIXTURE_DELAY_MS ?? "300"}ms response parts. Not a cold-machine, long-history or process-RSS benchmark.`;
  await writeFile(join(output, "performance-sample.json"), JSON.stringify(measurements, null, 2));
  await send("Emulation.clearDeviceMetricsOverride");
  await wait(editor, inputs => inputs.length === 1 && inputs[0].active, "editor after viewport restoration");
  await replaceNamedEditor("Message", "paired draft 你好");
  measurements.pairedAgentDraft = (await editor()).find(input => input.label === "Message");
  await writeFile(join(output, "paired-agent-1280x1000.png"),
    Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  await writeFile(join(output, "ui-coverage.json"), JSON.stringify({
    connectionEntry: measurements.connectionEntry, automaticEntry: measurements.automaticEntry,
    invalidOriginRejected: measurements.invalidOriginRejected, rootEntries: measurements.rootEntries,
    rootDeleteAxFocused: measurements.rootDeleteAxFocused, narrowAgentEditor: measurements.narrowAgentEditor,
    pairedAgentDraft: measurements.pairedAgentDraft,
    pairedNewSessionDraft: measurements.pairedNewSessionDraft,
    runningSpinners: measurements.runningSpinners,
  }, null, 2));
  await pointer(disconnect);
  if (automaticCheck) await waitText("Local Kodex session disconnected");
  else await button("Choose scoped pairing record");
  await wait(editor, inputs => inputs.length === 0, "disconnect unmounts the shared application editors");
  await delay(500);
  const heartbeatCount = requests.filter(value => value.endsWith("/heartbeat")).length;
  await delay(21_000);
  assert.equal(requests.filter(value => value.endsWith("/heartbeat")).length, heartbeatCount,
    "Disconnect must cancel the frontend-owned renewal task.");
  assert.equal((await status()).phase, "ready", "Disconnect is not backend Stop.");
  const reloadRequests = requests.length;
  const reloadSockets = sockets.length;
  await send("Page.reload");
  if (automaticCheck) {
    await waitText("Backend: Connected");
    await click("Disconnect");
    await waitText("Local Kodex session disconnected");
  } else {
    await button("Choose scoped pairing record");
  }
  await delay(500);
  if (!automaticCheck) {
    assert.equal(sockets.length, reloadSockets);
    assert.ok(requests.slice(reloadRequests).every(value => !value.endsWith("/heartbeat")));
  }
  assert.equal(await evaluate("localStorage.length"), 0);
  assert.equal(await evaluate("sessionStorage.length"), automaticCheck ? 1 : 0);
  await wait(() => sensitiveRequests.size + responseScanner.pendingCount, value => value === 0,
    "all credential-bearing HTTP responses finish their strict scan");
  await responseScanner.drain();
  assert.deepEqual(errors, []);
  assert.equal(secretLeak, false,
    "Management/OpenAI credentials must never reach the page; MCP secrets may only cross the existing authenticated business RPC.");
  assert.deepEqual(responseScanner.failures, [], "Sensitive response scans cannot silently skip unread bodies.");
  assert.ok(sensitiveBodyResponses > 0);
  assert.equal(responseScanner.counts.bodies, sensitiveBodyResponses,
    "Every body-bearing sensitive HTTP response must have been inspected before resuming unchanged.");
  assert.ok(enabledAccessibility.every(Boolean), "Ready companion controls must be accessibility-enabled.");
  if (capabilityCheck) {
    assert.ok(capabilityMethods.every(name => rpcEvidence[name] > 0), "Functional UI must use the actual typed business RPCs.");
    assert.equal(capturedLaunchBase, true, "Directory/Context requests must carry the immutable admitted launch base.");
    for (const type of ["reserve_callback", "bind_callback", "await_callback", "open_registered_authorization", "observe_error"]) {
      assert.equal(hostEvidence[type] ?? 0, 0, "Retired frontend effects must not traverse the Native bridge.");
    }
  }
  await writeFile(join(output, "product-result.json"), JSON.stringify({
    browser, sockets: sockets.length, fonts: [...fonts], errors,
    realProductionHost: true, exactInstanceRecovery: true, scopedPairingOnly: !automaticCheck,
    disconnectCancelsRenewalWithoutStoppingBackend: true, reloadRequiresExplicitImport: !automaticCheck,
    automaticReloadUsesScopedCapability: automaticCheck,
    managementAndOpenAiSecretsAbsentFromAllPageTraffic: true,
    mcpCredentialsConfinedToExistingAuthenticatedBusinessRpc: true,
    securityResponseReadsComplete: true, securityResponseCounts: responseScanner.counts,
    typedRpcEvidence: rpcEvidence, nativeBridgeOperations: hostEvidence, capturedLaunchBase,
    gaps: ["Frontend ObserveError runner has no deterministic page-originated error trigger.",
      "Relative directory input cannot currently be entered through the page picker; launch-base query is checked, not relative resolution.",
      "Not full cancellation/denial/lease-expiry, accepted-owner-disposal or cross-platform acceptance."],
    capabilityFunctions: capabilityCheck ? measurements : "Not exercised; run check-capabilities.mjs",
  }, null, 2));
  console.log("PASS Web UI regression: scoped entry/heartbeat/RPC, cursor, stream, recovery and disconnect/reload; see explicit capability coverage/gaps");
} catch (failure) {
  if (capabilityCheck && measurements.capabilityStage?.startsWith("directory-")) {
    try {
      const nodes = await tree();
      scanSensitive(nodes);
      if (!secretLeak) {
        const names = nodes.filter(node => node.name?.value).map(node => ({
          role: node.role?.value, ignored: node.ignored === true,
          name: node.name.value.replaceAll(process.env.KODEX_CAPABILITY_SANDBOX, "[owned-sandbox]"),
        }));
        await writeFile(join(output, "directory-failure.json"), JSON.stringify(names, null, 2), { mode: 0o600 });
        await writeFile(join(output, "directory-failure.png"),
          Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"), { mode: 0o600 });
      }
    } catch { /* Preserve the original strict failure when the private page is already gone. */ }
  }
  await writeFile(join(output, "failure-events.json"), JSON.stringify({
    failure: capabilityCheck ? "Strict production capability/UI check failed (sensitive diagnostics suppressed)" : failure.message,
    capabilityStage: measurements.capabilityStage,
    directoryRestoreClick: measurements.directoryRestoreClick,
    probeLocations: capabilityCheck ? [...String(failure.stack ?? "").matchAll(/check-(?:product|capabilities)\.mjs:\d+:\d+/g)]
      .map(match => match[0]).slice(0, 8) : undefined,
    capabilityMethods: capabilityCheck ? { rpc: rpcEvidence, host: hostEvidence } : undefined,
    securityReadFailures: responseScanner.failures,
    events: events.slice(-100).map(event => ({
      method: event.method,
      status: event.params?.response?.status,
      path: safeNetworkPath(event.params?.request?.url ?? event.params?.response?.url),
    })),
  }, null, 2), { mode: 0o600 });
  if (!capabilityCheck) await writeFile(join(output, "chrome.stderr.log"), chromeErrors, { mode: 0o600 });
  if (!capabilityCheck && sessionId && socket?.readyState === WebSocket.OPEN) {
    try {
      await writeFile(join(output, "product-failure.png"),
        Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
      await writeFile(join(output, "product-failure.json"), JSON.stringify({
        errors, sockets: sockets.length, lastEvents: events.slice(-12).map(event => event.method),
        editors: await editor(), enabledAccessibility,
        visibleNames: (await tree()).map(node => node.name?.value).filter(Boolean),
        navigation: await Promise.all((await tree()).filter(node => node.name?.value?.startsWith("Settings page "))
          .map(async node => ({ name: node.name.value, role: node.role?.value, ignored: node.ignored,
            properties: node.properties, point: await position(node).catch(() => null) }))),
        focus: (await tree()).filter(node => node.properties?.some(property =>
          property.name === "focused" && property.value.value === true)).map(node => ({
          role: node.role?.value, name: node.name?.value,
        })),
      }, null, 2));
    } catch { /* Browser may have exited before evidence capture. */ }
  }
  throw capabilityCheck ? new Error("Production capability/UI probe failed; sensitive details suppressed.") : failure;
} finally {
  for (const request of pending.values()) {
    clearTimeout(request.timeout);
    request.fail(new Error("Owned browser connection closed."));
  }
  pending.clear();
  if (socket) socket.close();
  chrome.kill("SIGTERM");
  await Promise.race([exited, delay(5000)]);
  if (chrome.exitCode === null) { chrome.kill("SIGKILL"); await exited; }
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

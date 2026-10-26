import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const require = createRequire(resolve(process.argv[2]));
const WebSocket = require("ws");
const output = resolve(process.argv[3] ?? ".");
const token = process.env.KODEX_WEB_FIXTURE_TOKEN ?? "";
const descriptorOnly = process.env.KODEX_DESCRIPTOR_ONLY === "1";
const inputOnly = process.env.KODEX_INPUT_ONLY === "1";
const formOnly = process.env.KODEX_FORM_ONLY === "1";
const recoveryOnly = process.env.KODEX_RECOVERY_ONLY === "1";
const rootOnly = process.env.KODEX_ROOT_ONLY === "1";
const webUrl = process.env.KODEX_FIXTURE_WEB_URL ?? "http://127.0.0.1:18795/";
assert.ok([descriptorOnly, inputOnly, formOnly, recoveryOnly, rootOnly].filter(Boolean).length <= 1,
  "Select one targeted fixture mode.");
if (!descriptorOnly && !inputOnly && !formOnly) assert.ok(token.length >= 32, "Supply a runtime-only fixture token.");
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(output, "owned-chrome-"));
const chromeLog = createWriteStream(join(output, "chrome.log"));
const chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
  "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
  "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
  `--user-data-dir=${profile}`, "--window-size=1280,1000",
  "--force-renderer-accessibility", "--use-gl=angle", "--use-angle=swiftshader",
  "--enable-unsafe-swiftshader", "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });
chrome.stderr.pipe(chromeLog);
const chromeExit = new Promise((done) => chrome.once("exit", done));
let socket;
let id = 0;
const pending = new Map();
const failures = [];
const pages = new Map();

function send(method, params = {}, sessionId) {
  const requestId = ++id;
  return new Promise((done, fail) => {
    const timeout = setTimeout(() => { pending.delete(requestId); fail(new Error(`${method} timed out`)); }, 20_000);
    pending.set(requestId, { done, fail, timeout });
    socket.send(JSON.stringify({ id: requestId, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}

async function waitFor(read, matches, label, milliseconds = 30_000) {
  const until = Date.now() + milliseconds;
  let value;
  while (Date.now() < until) {
    value = await read();
    if (matches(value)) return value;
    await delay(100);
  }
  throw new Error(`${label}: timed out`);
}

async function page(fragment = "", url = webUrl) {
  const { browserContextId } = await send("Target.createBrowserContext", { disposeOnDetach: true });
  const { targetId } = await send("Target.createTarget", { url: "about:blank", browserContextId });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  const state = { sessionId, browserContextId, fonts: new Set(), sockets: [] };
  pages.set(sessionId, state);
  await send("Runtime.enable", {}, sessionId);
  await send("Network.enable", {}, sessionId);
  await send("Accessibility.enable", {}, sessionId);
  await send("Page.navigate", { url: `${url}${fragment}` }, sessionId);
  return state;
}

async function tree(page) {
  return (await send("Accessibility.getFullAXTree", {}, page.sessionId)).nodes;
}
function text(nodes) {
  return nodes.map((node) => `${node.name?.value ?? ""}\n${node.value?.value ?? ""}`).join("\n");
}
function assistantCount(nodes) {
  return nodes.filter((node) => node.role?.value === "StaticText" && node.name?.value === "Assistant").length;
}
async function waitText(page, expected) {
  return waitFor(() => tree(page), (nodes) => text(nodes).includes(expected), expected);
}
async function click(page, name) {
  const nodes = await waitFor(() => tree(page),
    (nodes) => nodes.some((node) => node.role?.value === "button" && node.name?.value === name), name);
  const node = nodes.find((node) => node.role?.value === "button" && node.name?.value === name);
  const { object } = await send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId }, page.sessionId);
  try {
    await send("Runtime.callFunctionOn", {
      objectId: object.objectId, functionDeclaration: "function() { this.click(); }",
    }, page.sessionId);
  } finally { await send("Runtime.releaseObject", { objectId: object.objectId }, page.sessionId); }
}
async function evaluate(page, expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true }, page.sessionId);
  assert.equal(result.exceptionDetails, undefined);
  return result.result.value;
}
async function key(page, name, code, modifiers = 0) {
  const physical = name.length === 1 ? `Key${name.toUpperCase()}` : name;
  const values = { key: name, code: physical, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, modifiers };
  await send("Input.dispatchKeyEvent", {
    ...values, type: "keyDown",
    ...(name === "Enter" ? { text: "\r", unmodifiedText: "\r" } : {}),
  }, page.sessionId);
  await send("Input.dispatchKeyEvent", { ...values, type: "keyUp" }, page.sessionId);
}
async function insert(page, value) {
  await send("Input.insertText", { text: value }, page.sessionId);
  await waitText(page, value);
}
async function close(page) {
  await send("Target.disposeBrowserContext", { browserContextId: page.browserContextId });
  pages.delete(page.sessionId);
}

async function editors(page) {
  return evaluate(page, `(() => {
    const result = [];
    const visit = root => {
      for (const element of root.querySelectorAll("*")) {
        if (element.tagName === "TEXTAREA") result.push({
          label: element.getAttribute("aria-label"), value: element.value,
          cursor: element.selectionEnd, active: element.getRootNode().activeElement === element,
          disabled: element.disabled, visibility: getComputedStyle(element).visibility,
          color: getComputedStyle(element).color, lineHeight: getComputedStyle(element).lineHeight,
          fontSize: getComputedStyle(element).fontSize, borderWidth: getComputedStyle(element).borderTopWidth,
        });
        if (element.shadowRoot) visit(element.shadowRoot);
      }
    };
    visit(document);
    return result;
  })()`);
}
async function point(page, name) {
  const nodes = await waitFor(() => tree(page),
    nodes => nodes.some(node => node.name?.value === name && node.backendDOMNodeId), name);
  const node = nodes.find(node => node.name?.value === name && node.backendDOMNodeId);
  const { model } = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }, page.sessionId);
  const q = model.content;
  return { x: (q[0] + q[2] + q[4] + q[6]) / 4, y: (q[1] + q[3] + q[5] + q[7]) / 4 };
}
async function pointer(page, position, button = "left") {
  await send("Input.dispatchMouseEvent", { ...position, type: "mouseMoved" }, page.sessionId);
  for (const type of ["mousePressed", "mouseReleased"]) {
    await send("Input.dispatchMouseEvent", { ...position, type, button, clickCount: 1 }, page.sessionId);
  }
}
async function waitVisualIdle(page) {
  let previous;
  let unchanged = 0;
  // Stabilize scene geometry and animated Material menus, never delay between input events.
  await waitFor(async () => (await send("Page.captureScreenshot", { format: "png" }, page.sessionId)).data,
    image => {
      unchanged = image === previous ? unchanged + 1 : 0;
      previous = image;
      return unchanged >= 2;
    }, "root/menu pixels settled", 5_000);
}
async function formCheck() {
  const command = process.platform === "darwin" ? 4 : 2;
  const selectAll = async target => {
    const values = { key: "a", code: "KeyA", windowsVirtualKeyCode: 65, modifiers: command };
    await send("Input.dispatchKeyEvent", { ...values, type: "rawKeyDown", commands: ["selectAll"] }, target.sessionId);
    await send("Input.dispatchKeyEvent", { ...values, type: "keyUp" }, target.sessionId);
  };
  const control = await page("", "about:blank");
  await evaluate(control, `(() => {
    const input = document.createElement("textarea");
    input.value = "plain control";
    document.body.appendChild(input); input.focus(); input.setSelectionRange(13, 13);
  })()`);
  await key(control, "a", 65, command);
  const nativeShortcutSelected = await evaluate(control,
    "document.activeElement.selectionStart === 0 && document.activeElement.selectionEnd === 13");
  await selectAll(control);
  assert.equal(await evaluate(control,
    "document.activeElement.selectionStart === 0 && document.activeElement.selectionEnd === 13"), true,
    "The CDP editing command must first work on a plain HTML control.");
  await writeFile(join(output, "selection-control.json"), JSON.stringify({
    nativeShortcutSelected, commandSelectsAll: true, platform: process.platform,
  }, null, 2));
  await close(control);
  const live = await page();
  const activeField = label => waitFor(() => editors(live),
    inputs => inputs.some(input => input.label === label && input.active && !input.disabled),
    `focused field ${label}`);
  await click(live, "Open native form field fixture");
  await waitText(live, "Native form fields — isolated fixture");
  await click(live, "Focus fixture answer");
  await activeField("Fixture answer");
  await selectAll(live);
  const literal = "A\n😀 <img src=x onerror=alert(1)>";
  await send("Input.insertText", { text: literal }, live.sessionId);
  await waitFor(() => editors(live),
    inputs => inputs.some(input => input.label === "Fixture answer" && input.value === literal),
    "literal multiline paste into controlled value field");
  await waitText(live, `Answer UTF-16 length=${literal.length}`);
  for (const [editingCommand, expected] of [["undo", "Editable answer"], ["redo", literal]]) {
    const values = { key: "z", code: "KeyZ", windowsVirtualKeyCode: 90,
      modifiers: command | (editingCommand === "redo" ? 8 : 0) };
    await send("Input.dispatchKeyEvent", { ...values, type: "rawKeyDown", commands: [editingCommand] }, live.sessionId);
    await send("Input.dispatchKeyEvent", { ...values, type: "keyUp" }, live.sessionId);
    await waitFor(() => editors(live),
      inputs => inputs.some(input => input.label === "Fixture answer" && input.value === expected),
      `native ${editingCommand} survives controlled field echoes`);
    await waitText(live, `Answer UTF-16 length=${expected.length}`);
  }
  const selection = await evaluate(live, `(() => {
    function find(root) {
      for (const element of root.querySelectorAll("*")) {
        if (element.tagName === "TEXTAREA" && element.getAttribute("aria-label") === "Fixture answer") return element;
        const nested = element.shadowRoot && find(element.shadowRoot);
        if (nested) return nested;
      }
    }
    const input = find(document);
    input.setSelectionRange(2, 4, "backward");
    input.dispatchEvent(new Event("select"));
    return { start: input.selectionStart, end: input.selectionEnd, direction: input.selectionDirection,
      children: input.children.length };
  })()`);
  assert.deepEqual(selection, { start: 2, end: 4, direction: "backward", children: 0 });
  await key(live, "Enter", 13);
  await activeField("Fixture feedback");
  await selectAll(live);
  await send("Input.imeSetComposition", { text: "ni", selectionStart: 2, selectionEnd: 2 }, live.sessionId);
  await key(live, "Enter", 13);
  await waitText(live, "Field submissions=0; enabled=true");
  await send("Input.insertText", { text: "你好" }, live.sessionId);
  await key(live, "Enter", 13);
  await waitText(live, "Field submissions=1; enabled=true");
  await click(live, "Focus fixture answer");
  await activeField("Fixture answer");
  await key(live, "Tab", 9);
  await activeField("Fixture feedback");
  await key(live, "Tab", 9, 8);
  await activeField("Fixture answer");
  await key(live, "Tab", 9);
  await activeField("Fixture feedback");
  await key(live, "Tab", 9);
  await key(live, "Enter", 13);
  await waitText(live, "Field submissions=2; enabled=true");
  const rejected = "R".repeat(literal.length);
  const ownerLabel = value => `Answer owner units: ${value.split("").map(char => char.charCodeAt(0)).join(",")}`;
  await click(live, "Reject next fixture answer edit");
  await click(live, "Focus fixture answer");
  await activeField("Fixture answer");
  await selectAll(live);
  await send("Input.insertText", { text: rejected }, live.sessionId);
  await waitFor(() => editors(live), inputs => inputs.length === 2 && inputs.every(input => input.disabled) &&
    inputs.some(input => input.label === "Fixture answer" && input.value === literal),
    "disabled field restores the unmodified owner after rejection");
  await waitText(live, ownerLabel(literal));
  await click(live, "Toggle fixture fields enabled");
  await waitFor(() => editors(live), inputs => inputs.length === 2 && inputs.every(input => !input.disabled),
    "native fields re-enabled before requesting focus");
  await click(live, "Focus fixture answer");
  await activeField("Fixture answer");
  await selectAll(live);
  await send("Input.insertText", { text: rejected }, live.sessionId);
  await waitText(live, ownerLabel(rejected));
  await selectAll(live);
  await send("Input.insertText", { text: literal }, live.sessionId);
  await waitText(live, ownerLabel(literal));
  await click(live, "Show fixture overlay boundary");
  await activeField("Fixture overlay field");
  await waitFor(() => editors(live), inputs => inputs.length === 3 &&
    inputs.filter(input => input.disabled && input.visibility === "hidden").length === 2,
    "underlying fields hidden behind overlay");
  await send("Input.insertText", { text: "overlay 中文" }, live.sessionId);
  await click(live, "Close fixture overlay boundary");
  await waitFor(() => editors(live), inputs => inputs.length === 2 &&
    inputs.every(input => !input.disabled && input.visibility === "visible") &&
    inputs.some(input => input.label === "Fixture answer" && input.value === literal),
    "overlay disposal retains underlying draft");
  await click(live, "Open fixture Hook dialog");
  await activeField("Name");
  await send("Input.insertText", { text: "isolated-name" }, live.sessionId);
  await waitFor(() => editors(live), inputs => inputs.some(input => input.label === "Name" &&
    input.value === "isolated-name"), "production Hook name field");
  // A real production Dialog, rather than the fixture's inline overlay boundary.
  await waitFor(() => editors(live), inputs => inputs.filter(input =>
    input.label.startsWith("Fixture") && input.disabled && input.visibility === "hidden").length === 2,
    "dialog obscures underlying fields");
  await send("Emulation.setDeviceMetricsOverride", {
    width: 900, height: 500, deviceScaleFactor: 1, mobile: false,
  }, live.sessionId);
  const commandGeometry = () => evaluate(live, `(() => {
    function find(root) {
      for (const element of root.querySelectorAll("*")) {
        if (element.tagName === "TEXTAREA" && element.getAttribute("aria-label") === "Command") return element;
        const nested = element.shadowRoot && find(element.shadowRoot);
        if (nested) return nested;
      }
    }
    const input = find(document);
    if (!input) return null;
    const rect = input.getBoundingClientRect();
    const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
    let hit = document.elementFromPoint(x, y);
    while (hit?.shadowRoot?.elementFromPoint(x, y) && hit.shadowRoot.elementFromPoint(x, y) !== hit)
      hit = hit.shadowRoot.elementFromPoint(x, y);
    return { x, y, hit: hit === input, width: rect.width, height: rect.height };
  })()`);
  // Scroll the real dialog, rather than invoking a semantic action on clipped content.
  let geometry;
  for (let attempt = 0; attempt < 8; attempt++) {
    geometry = await commandGeometry();
    if (geometry?.hit && geometry.y > 0 && geometry.y < 500) break;
    await send("Input.dispatchMouseEvent", {
      type: "mouseWheel", x: 450, y: 250, deltaX: 0, deltaY: 160,
    }, live.sessionId);
    await delay(100);
  }
  assert.ok(geometry?.hit && geometry.width > 0 && geometry.height === 24,
    "The production dialog must expose its native 24px input line inside the Material field after scrolling.");
  await pointer(live, { x: geometry.x, y: geometry.y });
  await activeField("Command");
  await send("Input.insertText", { text: "literal command\n中文 😀" }, live.sessionId);
  await waitFor(() => editors(live), inputs => inputs.some(input => input.label === "Command" &&
    input.value === "literal command\n中文 😀") && inputs.some(input => input.label === "Name" &&
    input.value === "isolated-name"), "both controlled Hook fields retain their drafts");
  const hookImage = await send("Page.captureScreenshot", { format: "png" }, live.sessionId);
  await writeFile(join(output, "material-hook-fields.png"), Buffer.from(hookImage.data, "base64"));
  await key(live, "Escape", 27);
  try {
    await waitFor(() => editors(live), inputs => inputs.length === 2 && inputs.every(input => !input.disabled),
      "real dialog disposal from native field", 5_000);
  } catch (error) {
    const before = await editors(live);
    const canvasFocused = await evaluate(live, `(() => {
      function focus(root) {
        const canvas = root.querySelector("canvas");
        if (canvas) { canvas.focus(); return true; }
        for (const element of root.querySelectorAll("*"))
          if (element.shadowRoot && focus(element.shadowRoot)) return true;
        return false;
      }
      return focus(document);
    })()`);
    await key(live, "Escape", 27);
    const canvasEscapeCloses = await waitFor(() => editors(live),
      inputs => inputs.length === 2 && inputs.every(input => !input.disabled),
      "canvas Escape control", 5_000).then(() => true, () => false);
    await writeFile(join(output, "escape-control.json"), JSON.stringify({ before, canvasFocused, canvasEscapeCloses }, null, 2));
    throw error;
  }
  await send("Emulation.setDeviceMetricsOverride", {
    width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false,
  }, live.sessionId);
  await click(live, "Back from form fixture");
  await click(live, "Open native form field fixture");
  const reset = await waitFor(() => editors(live), inputs => inputs.length === 2 &&
    inputs.some(input => input.label === "Fixture answer" && input.value === "Editable answer"),
    "new owner remount resets fields");
  assert.ok(reset.every(input => input.color === "rgb(25, 28, 35)" &&
    input.fontSize === "16px" && input.lineHeight === "24px" && input.borderWidth === "0px"),
    "Native text must use the light Material foreground/type scale, with its outline owned by Compose.");
  const lightImage = await send("Page.captureScreenshot", { format: "png" }, live.sessionId);
  await writeFile(join(output, "material-fields-light.png"), Buffer.from(lightImage.data, "base64"));
  await send("Emulation.setEmulatedMedia", {
    features: [{ name: "prefers-color-scheme", value: "dark" }],
  }, live.sessionId);
  const dark = await waitFor(() => editors(live),
    inputs => inputs.length === 2 && inputs.every(input => input.color === "rgb(225, 226, 233)"),
    "native foreground follows the dark Material theme");
  assert.ok(dark.every(input => input.fontSize === "16px" && input.lineHeight === "24px"));
  await waitVisualIdle(live);
  const darkImage = await send("Page.captureScreenshot", { format: "png" }, live.sessionId);
  await writeFile(join(output, "material-fields-dark.png"), Buffer.from(darkImage.data, "base64"));
  assert.equal(live.sockets.length, 0);
  assert.deepEqual(failures, []);
  await writeFile(join(output, "form-fields.json"), JSON.stringify({
    editors: reset, failures, nativeShortcutSelected, selectionSetup: "CDP selectAll editing command",
  }, null, 2));
  await close(live);
  console.log("PASS compiled value fields, Enter, bidirectional Tab, disable, overlay, production Hook dialog and owner remount");
}
async function rootCheck() {
  const live = await page(`#${token}`);
  await send("Emulation.setDeviceMetricsOverride", {
    width: 1200, height: 1000, deviceScaleFactor: 1, mobile: false,
  }, live.sessionId);
  await click(live, "Open real RPC fixture");
  await click(live, "Open full graphical root");
  // A focused slice editor is not evidence that the graphical root has mounted.
  await waitText(live, "Isolated full graphical root — not product authentication");
  const oneEditor = predicate => waitFor(() => editors(live),
    inputs => inputs.length === 1 && predicate(inputs[0]), "root editor");
  await oneEditor(input => input.active);
  await send("Input.insertText", { text: "abcd" }, live.sessionId);
  await key(live, "ArrowLeft", 37);
  await key(live, "ArrowLeft", 37);
  await send("Input.insertText", { text: "X" }, live.sessionId);
  await oneEditor(input => input.value === "abXcd" && input.cursor === 3);
  await waitText(live, "cursor=3; text=abXcd");
  const message = "  graphical root — 中文 995  ";
  await key(live, "a", 65, 2);
  await send("Input.insertText", { text: message }, live.sessionId);
  await key(live, "Enter", 13);
  await waitText(live, "test answer — 你好");
  const submitted = await waitText(live, "running=false; submitting=false; cursor=0; text=");
  assert.equal(submitted.filter(node => node.role?.value === "StaticText" && node.name?.value === message).length, 1);
  await waitVisualIdle(live);
  const settings = await point(live, "Settings");
  const userMessage = await point(live, message);
  await pointer(live, await point(live, "Session tab: tab-1"), "right");
  await oneEditor(input => input.disabled && input.visibility === "hidden");
  await pointer(live, await point(live, "Application menu action: rename"));
  await waitFor(() => editors(live), inputs => inputs.length === 2 && inputs[1].active &&
    inputs[0].disabled && inputs[0].visibility === "hidden", "direct Rename focus");
  await key(live, "a", 65, 2);
  await send("Input.insertText", { text: "Graphical direct rename" }, live.sessionId);
  await key(live, "Enter", 13);
  await oneEditor(input => input.active && !input.disabled && input.value === "");
  await pointer(live, settings);
  await waitText(live, "Graphical direct rename");
  await pointer(live, await point(live, "Rename"));
  await waitFor(() => editors(live), inputs => inputs.length === 2 && inputs[1].active, "nested Rename focus");
  await key(live, "a", 65, 2);
  await send("Input.insertText", { text: "Graphical nested rename" }, live.sessionId);
  await key(live, "Enter", 13);
  await oneEditor(input => input.disabled && input.visibility === "hidden" && !input.active && input.value === "");
  assert.equal(await evaluate(live, `(() => {
    let element = document.activeElement;
    while (element?.shadowRoot?.activeElement) element = element.shadowRoot.activeElement;
    return element?.tagName;
  })()`), "CANVAS", "Nested DOM removal must restore the canvas keyboard route.");
  await key(live, "Escape", 27);
  await oneEditor(input => input.active && input.visibility === "visible" && !input.disabled);
  await pointer(live, settings);
  await waitText(live, "Graphical nested rename");
  await pointer(live, await point(live, "Close"));
  await oneEditor(input => input.active && input.visibility === "visible" && !input.disabled);
  await pointer(live, userMessage, "right");
  await pointer(live, await point(live, "Application menu action: revert-and-edit"));
  await oneEditor(input => input.value === message && input.cursor === message.length && input.active);
  await delay(300);
  await oneEditor(input => input.value === message && input.active);
  await pointer(live, settings);
  await pointer(live, await point(live, "Close"));
  await oneEditor(input => input.value === message && input.active && !input.disabled);
  await pointer(live, settings);
  await pointer(live, await point(live, "Settings page General"));
  await pointer(live, await point(live, "New line key: Shift+Enter"));
  await key(live, "Escape", 27);
  await oneEditor(input => input.disabled && input.visibility === "hidden");
  await waitVisualIdle(live);
  await key(live, "Escape", 27);
  const finalEditors = await oneEditor(input => input.active && input.value === message && !input.disabled);
  await waitVisualIdle(live);
  assert.deepEqual(failures, []);
  assert.equal(live.sockets.length, 1);
  const image = await send("Page.captureScreenshot", { format: "png" }, live.sessionId);
  await writeFile(join(output, "root-functional.png"), Buffer.from(image.data, "base64"));
  await writeFile(join(output, "root-functional.json"), JSON.stringify({ editors: finalEditors, failures }, null, 2));
  console.log("PASS root DOM cursor, submit, direct/nested Rename, pointer/keyboard dismissal and real revert-and-edit");
  // This is a separate real browser gate, not replaceable by the raster scene's semantics.
  const restored = await waitFor(() => tree(live),
    nodes => text(nodes).includes("Graphical projection:"), "browser AX owner restored after popup disposal", 5_000);
  await writeFile(join(output, "root-ax.json"), JSON.stringify(restored, null, 2));
  await close(live);
}

try {
  const active = await waitFor(
    () => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""),
    (value) => value.includes("\n"), "owned Chrome startup",
  );
  const [port, browserPath] = active.trim().split("\n");
  socket = new WebSocket(`ws://127.0.0.1:${port}${browserPath}`);
  socket.on("message", (bytes) => {
    const message = JSON.parse(bytes.toString());
    if (message.id) {
      const request = pending.get(message.id);
      if (!request) return;
      pending.delete(message.id);
      clearTimeout(request.timeout);
      if (message.error) request.fail(new Error(JSON.stringify(message.error)));
      else request.done(message.result);
    } else {
      const page = pages.get(message.sessionId);
      if (message.method === "Runtime.exceptionThrown") {
        const details = message.params.exceptionDetails;
        failures.push(details.exception?.description ?? details.text);
      }
      if (page && message.method === "Network.webSocketCreated") page.sockets.push(message.params.url);
      if (page && message.method === "Network.responseReceived") {
        const response = message.params.response;
        if (response.status === 200 && response.url.includes("/font/")) {
          page.fonts.add(response.url.split("/").at(-1));
        }
      }
    }
  });
  await new Promise((done, fail) => { socket.once("open", done); socket.once("error", fail); });
  console.log(`Browser ${(await send("Browser.getVersion")).product}`);

  if (formOnly) {
    await formCheck();
  } else if (rootOnly) {
    await rootCheck();
  } else if (inputOnly) {
    const editor = await page();
    await waitText(editor, "Kodex renderer smoke — isolated fixture");
    await key(editor, "a", 65, 2);
    await key(editor, "Backspace", 8);
    await send("Input.imeSetComposition", { text: "ni", selectionStart: 2, selectionEnd: 2 }, editor.sessionId);
    await waitText(editor, "ni");
    await key(editor, "Enter", 13);
    await waitText(editor, "Submitted 0:");
    await send("Input.imeSetComposition", { text: "你好", selectionStart: 2, selectionEnd: 2 }, editor.sessionId);
    await send("Input.insertText", { text: "你好" }, editor.sessionId);
    await waitText(editor, "你好");
    await key(editor, "Enter", 13);
    await waitText(editor, "Submitted 1: 你好");
    await key(editor, "Tab", 9);
    await key(editor, "Enter", 13);
    await waitText(editor, "Fixture submit key: CtrlEnter");
    await key(editor, "Tab", 9, 8);
    await key(editor, "a", 65, 2);
    await insert(editor, "key policy");
    await key(editor, "Enter", 13);
    await waitFor(() => tree(editor), (nodes) =>
      nodes.some((node) => node.role?.value === "textbox" &&
        node.value?.value === "key policy\n"), "plain Enter remains newline under CtrlEnter policy");
    await key(editor, "Enter", 13, 2);
    await waitText(editor, "Submitted 2: key policy");
    assert.equal(editor.sockets.length, 0);
    assert.deepEqual(failures, []);
    await close(editor);
    console.log("PASS composition, Enter guard, bidirectional Tab and CtrlEnter policy; not an OS IME test");
  } else if (descriptorOnly) {
    const check = await page();
    await waitText(check, "Kodex renderer smoke — isolated fixture");
    await click(check, "Check RPC descriptor assembly");
    const nodes = await waitFor(() => tree(check), (nodes) =>
      text(nodes).includes("All eight production RPC proxies constructed.") ||
      text(nodes).includes("Descriptor assembly failed:"), "production descriptor assembly");
    const result = text(nodes);
    assert.ok(result.includes("All eight production RPC proxies constructed."),
      result.split("\n").find((line) => line.includes("Descriptor assembly failed:")));
    await click(check, "Check RPC serialization");
    const serializers = text(await waitFor(() => tree(check), (nodes) =>
      text(nodes).includes("All fourteen RPC roots") ||
      text(nodes).includes("RPC serialization failed:"), "production runtime serialization"));
    assert.ok(serializers.includes("All fourteen RPC roots"),
      serializers.split("\n").find((line) => line.includes("RPC serialization failed:")));
    assert.equal(check.sockets.length, 0);
    await close(check);
    console.log("PASS real RpcServices descriptor assembly and runtime serializers without network");
  } else {
  const missing = await page();
  await waitText(missing, "Kodex renderer smoke — isolated fixture");
  await click(missing, "Open real RPC fixture");
  await waitText(missing, "No RPC was opened.");
  assert.equal(missing.sockets.length, 0);
  await close(missing);
  console.log("PASS missing runtime token does not open RPC");

  const live = await page(`#${token}`);
  await waitText(live, "Kodex renderer smoke — isolated fixture");
  assert.ok(await evaluate(live, "location.hash === ''"), "The runtime token fragment must be removed.");
  await click(live, "Open real RPC fixture");
  await waitText(live, "Connected — real DTOs");
  await waitText(live, "New Session singleton");
  await insert(live, "browser 995 — 新会话");
  await key(live, "Enter", 13);
  await waitText(live, "Persisted fixture Session:");
  await waitText(live, "Assistant responding");
  await waitFor(() => tree(live), (nodes) =>
    nodes.some((node) => node.name?.value === "Assistant") &&
    text(nodes).includes("test answer — 你好"), "committed model answer");
  console.log("PASS real WebSocket NewSession creation and streaming-to-committed History");
  assert.ok(live.fonts.has("noto_sans_sc.ttf"));
  assert.ok(live.fonts.has("noto_sans_math.ttf"));
  assert.equal(live.sockets.length, 1);
  assert.equal(live.sockets[0], "ws://127.0.0.1:18796/fixture");
  const screenshot = await send("Page.captureScreenshot", { format: "png" }, live.sessionId);
  await writeFile(join(output, "history.png"), Buffer.from(screenshot.data, "base64"));
  await waitText(live, "Persisted Composer: running=false; submitting=false");

  if (recoveryOnly) {
    await insert(live, "response continues across transport");
    await key(live, "Enter", 13);
    await waitText(live, "Assistant responding");
    await click(live, "Drop fixture transport");
    await waitText(live, "Fixture connection: Connected; generation=2; discarded replies=0");
    await waitFor(() => tree(live), (nodes) =>
      assistantCount(nodes) === 2, "recovered committed answer");
    await waitText(live, "Persisted Composer: running=false; submitting=false");
    await waitText(live, "Fixture write attempts: appends=2; resumes=2");
    await waitText(live, "Open fixture tabs: 1");
    console.log("PASS transport replacement reactivates the retained Session and its state/History subscriptions");

    await click(live, "Discard next accepted append reply");
    await insert(live, "unknown accepted append 995");
    await key(live, "Enter", 13);
    await waitText(live, "Fixture connection: Connected; generation=3; discarded replies=1");
    await waitFor(() => tree(live), (nodes) => {
      const failed = nodes.find((node) => node.name?.value?.startsWith("Persisted lifecycle: Failed"));
      assert.equal(failed, undefined, failed?.name.value);
      return text(nodes).includes("unknown accepted append 995");
    }, "recovered accepted append");
    await waitText(live, "Fixture write attempts: appends=3; resumes=2");
    const unknown = await tree(live);
    assert.equal(assistantCount(unknown), 2);
    assert.equal(unknown.filter((node) => node.role?.value === "StaticText" &&
      node.name?.value === "unknown accepted append 995").length, 1, "Accepted message is present exactly once.");
    await waitText(live, "Persisted Composer: running=false; submitting=false");
    await insert(live, "explicit action after reconnect");
    await key(live, "Enter", 13);
    await waitText(live, "History slice load: Ready; bounded rows=7");
    await waitText(live, "History window: newest=7;");
    await waitText(live, "Persisted Composer: running=false; submitting=false");
    assert.ok(assistantCount(await tree(live)) >= 1, "The newest committed answer is rendered; older rows may be offscreen.");
    await waitText(live, "Fixture write attempts: appends=4; resumes=3");
    assert.equal(live.sockets.length, 3);
    console.log("PASS discarded accepted append is not replayed or resumed; explicit later submission works");
  } else {
  await insert(live, "persisted Composer — 继续");
  await key(live, "Enter", 13);
  await waitText(live, "persisted Composer — 继续");
  await waitFor(() => tree(live), (nodes) =>
    assistantCount(nodes) === 2,
  "second committed answer");
  console.log("PASS shared persisted Composer appends through real RPC");

  await click(live, "Return to singleton draft");
  await waitText(live, "New Session singleton");
  await insert(live, "abcd");
  await waitText(live, "Singleton projection: cursor=4; text=abcd");
  await key(live, "ArrowLeft", 37);
  await key(live, "ArrowLeft", 37);
  await waitText(live, "Singleton projection: cursor=2; text=abcd");
  await insert(live, "X");
  await waitText(live, "Singleton projection: cursor=3; text=abXcd");
  await waitText(live, "Open fixture tabs: 1");
  const retained = (await tree(live)).find((node) =>
    node.role?.value === "button" && node.name?.value?.startsWith("Select retained Session "));
  assert.ok(retained);
  await click(live, retained.name.value);
  await waitText(live, "Persisted fixture Session:");
  await waitFor(() => tree(live), (nodes) =>
    assistantCount(nodes) === 2, "retained Session History");
  await click(live, "Return to singleton draft");
  await waitText(live, "Singleton projection: cursor=3; text=abXcd");
  console.log("PASS Application navigation retains the Session and singleton cursor");
  }
  await click(live, "Disconnect fixture and return");
  await waitText(live, "Kodex renderer smoke");
  assert.deepEqual(failures, []);
  await close(live);
  console.log("PASS clean fixture disconnect");
  }
} catch (error) {
  const runtimeErrors = JSON.stringify(failures, null, 2);
  await writeFile(join(output, "failure-errors.json"),
    token ? runtimeErrors.replaceAll(token, "[fixture-token-redacted]") : runtimeErrors);
  for (const page of pages.values()) {
    await writeFile(join(output, "failure-editors.json"), JSON.stringify(await editors(page).catch(() => []), null, 2));
    const snapshot = await tree(page).catch(() => []);
    const raw = JSON.stringify(snapshot, null, 2);
    await writeFile(join(output, "failure-ax.json"), token ? raw.replaceAll(token, "[fixture-token-redacted]") : raw);
    const image = await send("Page.captureScreenshot", { format: "png" }, page.sessionId).catch(() => null);
    if (image) await writeFile(join(output, "failure.png"), Buffer.from(image.data, "base64"));
  }
  throw error;
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    await send("Browser.close").catch(() => {});
    socket.close();
  }
  chrome.kill("SIGTERM");
  await Promise.race([chromeExit, delay(5_000)]).catch(() => {});
  if (chrome.exitCode === null && chrome.signalCode === null) { chrome.kill("SIGKILL"); await chromeExit; }
  chromeLog.end();
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

import assert from "node:assert/strict";
import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, mkdir, readFile, rm, writeFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const run = promisify(execFile);
const WebSocket = globalThis.WebSocket;
assert.ok(WebSocket, "Use Node 22+ with its built-in WebSocket client.");
assert.ok(process.argv[2], "Usage: node check-history-product.mjs OUTPUT_DIRECTORY");
assert.ok(process.env.CHROME_BIN, "Use an explicitly selected headless Chrome binary.");
assert.ok(process.env.KODEX_HISTORY_OCR, "Raster geometry requires the owned OCR helper.");
const output = resolve(process.argv[2]);
const record = process.env.KODEX_BROWSER_RECORD ?? "";
assert.match(record, /^\/tmp\/kodex-product-browser--?\d+\/\.locks\/backend\/connection\.json$/);
assert.equal((await stat(record)).mode & 0o777, 0o600);
const connection = JSON.parse(await readFile(record, "utf8"));
const launchFile = process.env.KODEX_BROWSER_LAUNCH_FILE;
assert.equal(launchFile, record.replace(".locks/backend/connection.json", "frontend-launch.private"),
  "History uses its own production automatic host, never a management-record import.");
assert.equal((await stat(launchFile)).mode & 0o777, 0o600);
const launchUrl = await readFile(launchFile, "utf8");
assert.match(launchUrl, /^http:\/\/127\.0\.0\.1:\d+\/web\/[0-9a-f]{64}\/#[0-9a-f]{64}$/);
assert.equal(process.env.KODEX_CHECK_HISTORY, "1", "Only the offline history fixture is allowed.");
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(output, "owned-history-chrome-"));
const chrome = spawn(process.env.CHROME_BIN, [
  "--headless=new", "--disable-dev-shm-usage", "--remote-debugging-address=127.0.0.1",
  "--disable-background-networking", "--disable-component-update", "--no-first-run",
  "--no-default-browser-check", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--window-size=1280,1000", "--force-renderer-accessibility",
  "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
  ...(process.platform === "darwin" ? ["--password-store=basic", "--use-mock-keychain"] : []),
  "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });
let stderr = "";
chrome.stderr.on("data", bytes => { stderr = (stderr + bytes).slice(-65536); });
chrome.on("error", error => { stderr += `\n${error.message}`; });
const exited = new Promise(done => chrome.once("close", done));
let socket, sessionId, next = 0;
const pending = new Map(), events = [], samples = [], inputs = [], errors = [], rpcFrames = [];
const measurements = {};
function frameLabels(value, result = new Set(), depth = 0) {
  if (depth > 8) return [...result];
  if (typeof value === "string" && /^[{[]/.test(value)) {
    try { frameLabels(JSON.parse(value), result, depth + 1); } catch {}
  } else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (["callableName", "method", "serviceType", "type"].includes(key) && typeof item === "string") result.add(item);
      frameLabels(item, result, depth + 1);
    }
  }
  return [...result];
}
function send(method, params = {}) {
  return new Promise((done, fail) => {
    const id = ++next;
    const timeout = setTimeout(() => { pending.delete(id); fail(new Error(`${method} timeout`)); }, 20000);
    pending.set(id, { done, fail, timeout });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
async function wait(read, matches, label, milliseconds = 45000) {
  const end = Date.now() + milliseconds;
  while (Date.now() < end) {
    const value = await read();
    if (matches(value)) return value;
    await delay(150);
  }
  throw new Error(`${label} timed out`);
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, userGesture: true });
  assert.equal(result.exceptionDetails, undefined);
  return result.result.value;
}
async function tree() { return (await send("Accessibility.getFullAXTree")).nodes; }
const names = nodes => nodes.map(node => node.name?.value ?? "");
async function waitText(value) { return wait(tree, nodes => names(nodes).some(name => name.includes(value)), value); }
async function click(name) {
  const nodes = await wait(tree, nodes => nodes.some(n => n.name?.value === name && n.backendDOMNodeId), name);
  const node = nodes.find(n => n.name?.value === name && n.backendDOMNodeId && n.role?.value === "button") ??
    nodes.find(n => n.name?.value === name && n.backendDOMNodeId);
  const { object } = await send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId });
  try {
    await send("Runtime.callFunctionOn", {
      objectId: object.objectId, functionDeclaration: "function(){this.click()}",
      userGesture: true,
    });
    inputs.push({ type: "semantics-DOM-click", name, at: Date.now() });
  } finally { await send("Runtime.releaseObject", { objectId: object.objectId }); }
}
async function key(key, code) {
  inputs.push({ type: "CDP-key", key, at: Date.now() });
  for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", {
    type, key, code: key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code,
    ...(type === "keyDown" && key === "Enter" ? { text: "\r", unmodifiedText: "\r" } : {}),
  });
}
async function composer(value, submit = true) {
  await evaluate(`(() => {
    function visit(root) {
      for(const e of root.querySelectorAll('*')) {
        if(e.tagName==='TEXTAREA' && e.getAttribute('aria-label')==='Message') {e.focus(); return true}
        if(e.shadowRoot && visit(e.shadowRoot)) return true;
      }
      return false;
    }
    if(!visit(document)) throw Error('Message DOM editor missing');
  })()`);
  await send("Input.insertText", { text: value });
  inputs.push({ type: "DOM-insertText", text: value, at: Date.now() });
  if (submit) await key("Enter", 13);
}
async function wheel(deltaY, count = 1) {
  for (let i = 0; i < count; i++) {
    inputs.push({ type: "CDP-wheel", deltaY, x: 650, y: 350, at: Date.now() });
    await send("Input.dispatchMouseEvent", {
      type: "mouseWheel", x: 650, y: 350, deltaX: 0, deltaY,
    });
    await delay(180);
  }
  await delay(350);
}
async function waitConnected() {
  await waitText("Backend: Connected");
  assert.equal(await evaluate("location.hash"), "", "Automatic bootstrap removes its launch fragment.");
}
async function sample(label) {
  await delay(350);
  const file = join(output, `${label}.png`);
  await writeFile(file, Buffer.from((await send("Page.captureScreenshot", { format: "png" })).data, "base64"));
  const ax = await tree();
  const dom = await evaluate(`(() => {
    const items=[];
    function visit(root) {
      for(const e of root.querySelectorAll('*')) {
        if(e.tagName==='CANVAS' || e.tagName==='TEXTAREA') {
          const r=e.getBoundingClientRect();
          items.push({tag:e.tagName,label:e.getAttribute('aria-label'),value:e.value,
            bounds:{x:r.x,y:r.y,width:r.width,height:r.height},
            scrollTop:e.scrollTop,scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,
            active:e.getRootNode().activeElement===e});
        }
        if(e.shadowRoot) visit(e.shadowRoot);
      }
    }
    visit(document); return {width:innerWidth,height:innerHeight,items};
  })()`);
  const ocr = process.env.KODEX_HISTORY_OCR
    ? JSON.parse((await run(process.env.KODEX_HISTORY_OCR, [file])).stdout) : null;
  const boxes = [];
  for (const node of ax.filter(n => n.name?.value?.startsWith("History entry ") ||
      n.name?.value === "Agent page history")) {
    const model = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId }).catch(() => null);
    boxes.push({ name: node.name.value, model });
  }
  const value = { label, at: Date.now(), dom, names: names(ax), historyAxBoxes: boxes, ocr,
    rpcFrames: rpcFrames.length };
  samples.push(value);
  await writeFile(join(output, `${label}.json`), JSON.stringify(value, null, 2));
  return value;
}
function pixels(sample, marker) {
  const normalized = value => value.toUpperCase().replace(/[^A-Z0-9]/g, "");
  return sample.ocr?.filter(line => normalized(line.text).includes(normalized(marker))) ?? [];
}
function assertBottom(sample, marker) {
  assert.ok(pixels(sample, marker).length, `${marker} must be painted, not only in AX`);
  const region = sample.historyAxBoxes.find(b => b.name === "Agent page history")?.model?.model;
  const entries = sample.historyAxBoxes.filter(b => b.name.startsWith("History entry ") && b.model?.model);
  const latest = entries.sort((a, b) => Number(b.name.slice(14)) - Number(a.name.slice(14)))[0]?.model?.model;
  assert.ok(region && latest, "Actual history and entry DOM geometry must be measurable");
  const bottom = box => Math.max(box.content[5], box.content[7]);
  assert.ok(Math.abs(bottom(region) - bottom(latest)) <= 1,
    `${marker} latest entry must meet history region bottom`);
}
function streamState(sample) {
  const numbers = strings => strings.flatMap(text =>
    [...text.toUpperCase().replace(/[^A-Z0-9]/g, "").matchAll(/HISTORYSTREAM(\d{3})/g)].map(m => Number(m[1])));
  const visible = numbers(sample.ocr.map(row => row.text));
  const semantic = numbers(sample.names);
  assert.ok(visible.length && semantic.length, "Stream requires both raster and semantics evidence");
  return { firstPixel: Math.min(...visible), lastPixel: Math.max(...visible),
    lastSemantic: Math.max(...semantic), lag: Math.max(...semantic) - Math.max(...visible) };
}
function streamReadingAnchors(before, after) {
  function rows(sample) {
    const region = sample.historyAxBoxes.find(b => b.name === "Agent page history")?.model?.model;
    assert.ok(region, "A reading anchor needs the measured history viewport");
    const top = Math.min(region.content[1], region.content[3]);
    const bottom = Math.max(region.content[5], region.content[7]);
    const result = new Map();
    for (const row of sample.ocr) {
      if (row.y <= top + row.height || row.y + row.height >= bottom) continue;
      const text = row.text.toUpperCase().replace(/[^A-Z0-9]/g, "");
      for (const match of text.matchAll(/HISTORYSTREAM(\d{3})/g)) result.set(match[1], row.y);
    }
    return result;
  }
  const oldRows = rows(before), newRows = rows(after);
  return [...oldRows].filter(([marker]) => newRows.has(marker)).map(([marker, beforeY]) => ({
    marker, beforeY, afterY: newRows.get(marker), deltaY: newRows.get(marker) - beforeY,
  }));
}
async function pointerClick(name) {
  const nodes = await waitText(name);
  const node = nodes.find(n => n.role?.value === "button" && n.name?.value === name);
  assert.ok(node?.backendDOMNodeId);
  const { model } = await send("DOM.getBoxModel", { backendNodeId: node.backendDOMNodeId });
  const q = model.content;
  const x = (q[0] + q[2] + q[4] + q[6]) / 4, y = (q[1] + q[3] + q[5] + q[7]) / 4;
  assert.ok(x > 0 && y > 0, "Pointer expansion requires actual layout, not AX-only fallback");
  inputs.push({ type: "CDP-pointer", name, x, y, at: Date.now() });
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent", {
    type, x, y, button: "left", clickCount: 1,
  });
}
async function openSession(title) {
  await click("Sessions");
  const nodes = await waitText(title);
  const exact = nodes.find(n => n.name?.value === title && n.backendDOMNodeId);
  if (exact) await click(title);
  else {
    const candidate = nodes.find(n => n.name?.value?.includes(title) && n.backendDOMNodeId);
    assert.ok(candidate, `Session ${title} has a DOM semantics target`);
    await click(candidate.name.value);
  }
  await delay(800);
}
let outcome = "incomplete";
try {
  const active = await wait(() => readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => ""),
    value => value.includes("\n"), "owned Chrome");
  const [port, path] = active.trim().split("\n");
  socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const item = pending.get(message.id);
      if (!item) return;
      pending.delete(message.id); clearTimeout(item.timeout);
      if (message.error) item.fail(new Error(JSON.stringify(message.error)));
      else item.done(message.result);
    } else {
      events.push(message);
      if (message.method === "Runtime.exceptionThrown") errors.push("Unhandled browser exception");
      if (["Network.webSocketFrameSent", "Network.webSocketFrameReceived"].includes(message.method)) {
        const payload = message.params.response?.payloadData ?? "";
        if (!payload.includes(connection.password)) rpcFrames.push({
          at: Date.now(), direction: message.method.endsWith("Sent") ? "sent" : "received",
          bytes: Buffer.byteLength(payload), labels: frameLabels(payload),
        });
      }
    }
  });
  await new Promise((done, fail) => {
    socket.addEventListener("open", done, { once: true });
    socket.addEventListener("error", fail, { once: true });
  });
  const target = await send("Target.createTarget", { url: "about:blank" });
  sessionId = (await send("Target.attachToTarget", { targetId: target.targetId, flatten: true })).sessionId;
  for (const domain of ["Runtime", "Network", "Accessibility", "Page"]) await send(`${domain}.enable`);
  await send("Page.addScriptToEvaluateOnNewDocument", { source: `(() => {
    const Native=WebSocket, owned=new Set();
    globalThis.WebSocket=new Proxy(Native,{construct(t,args,n){
      const s=Reflect.construct(t,args,n); owned.add(s); s.addEventListener('close',()=>owned.delete(s)); return s;
    }});
    globalThis.__historyCloseTransport=()=>{let count=0; for(const s of owned)
      if(new URL(s.url).pathname==='/browser/rpc'){s.close(1000,'offline history recovery');count++} return count};
  })()` });
  await send("Page.navigate", { url: launchUrl });
  await waitConnected();
  await sample("00-catalog");
  if (process.env.KODEX_HISTORY_PAGING_ONLY === "1") {
    await openSession("History many fixture");
    await waitText("HISTORY_MANY_LATEST");
    assertBottom(await sample("paging-01-first-open"), "HISTORY_MANY_LATEST");
    await wheel(-1600, 26);
    await waitText("HISTORY_ROW_000");
    await sample("paging-02-oldest");
    await wheel(2000, 35);
    await waitText("HISTORY_MANY_LATEST");
    assertBottom(await sample("paging-03-wheel-return-bottom"), "HISTORY_MANY_LATEST");
    await wait(tree, nodes => !names(nodes).some(name => name.includes("Scroll to latest")),
      "wheel return to latest must restore follow (Latest button disappears)", 5000);
    outcome = "paging-wheel-return-follow-passed";
  } else {
  await openSession("History sparse fixture");
  await waitText("HISTORY_SPARSE_LATEST");
  const sparse = await sample("01-sparse-first-open");
  assert.ok(pixels(sparse, "HISTORY_SPARSE_OLD").length && pixels(sparse, "HISTORY_SPARSE_LATEST").length,
    "Both sparse message markers must exist in actual screenshot OCR");
  assert.ok(pixels(sparse, "HISTORY_SPARSE_OLD")[0].y < pixels(sparse, "HISTORY_SPARSE_LATEST")[0].y,
    "Old sparse message pixels must be above latest");
  assertBottom(sparse, "HISTORY_SPARSE_LATEST");
  if (process.env.KODEX_HISTORY_DISCOVER === "1") {
    outcome = "discovery-only";
  } else {
    await composer("HISTORY_START_STREAM");
    await waitText("HISTORY_STREAM_005");
    await sample("02-stream-growth-follow");
    await waitText("HISTORY_STREAM_040");
    measurements.streamFollowing = streamState(await sample("02b-stream-taller-than-viewport"));
    assert.ok(measurements.streamFollowing.lag <= 2);
    await wheel(-600, 3);
    const streamAway = await sample("03-stream-user-away");
    measurements.streamAway = streamState(streamAway);
    await delay(2500);
    const streamAwayAfterGrowth = await sample("04-stream-away-after-growth");
    measurements.streamAwayAfterGrowth = streamState(streamAwayAfterGrowth);
    assert.ok(measurements.streamAway.lag > 8 && measurements.streamAwayAfterGrowth.lag > 8,
      "Growing stream must not force the user back to latest");
    measurements.streamAwayFirstVisibleRowDelta = measurements.streamAwayAfterGrowth.firstPixel -
      measurements.streamAway.firstPixel;
    measurements.streamReadingAnchors = streamReadingAnchors(streamAway, streamAwayAfterGrowth);
    assert.ok(measurements.streamReadingAnchors.length > 0, "The same actual visible text must exist in both raster samples");
    assert.ok(measurements.streamReadingAnchors.every(anchor => Math.abs(anchor.deltaY) <= 2),
      "A scrolled-away stream must preserve the same visible text pixels while growing");
    await wheel(1200, 8);
    await sample("05-stream-return-latest");
    await delay(1500);
    measurements.streamFollowingAfterReturn = streamState(await sample("05b-stream-follow-after-return"));
    assert.ok(measurements.streamFollowingAfterReturn.lag <= 2);
    await waitText("HISTORY_STREAM_079");
    assertBottom(await sample("06-stream-completed"), "HISTORY_STREAM_079");
    assert.equal(await evaluate("__historyCloseTransport()"), 1);
    await waitText("Backend: Connected");
    await delay(1500);
    await waitText("HISTORY_STREAM_079");
    assertBottom(await sample("07-recovered-same-session"), "HISTORY_STREAM_079");
    await openSession("History many fixture");
    await waitText("HISTORY_MANY_LATEST");
    assertBottom(await sample("08-many-first-open"), "HISTORY_MANY_LATEST");
    await wheel(-900, 7);
    await sample("09-many-older-page");
    await openSession("History sparse fixture");
    await sample("09a-switched-to-other-session");
    await openSession("History many fixture");
    await sample("09b-restored-retained-scroll");
    await wheel(-1600, 26);
    await waitText("HISTORY_ROW_000");
    await sample("10-many-oldest-page");
    await wheel(2000, 35);
    await waitText("HISTORY_MANY_LATEST");
    assertBottom(await sample("11-many-return-latest"), "HISTORY_MANY_LATEST");
    measurements.pagingReturnHasLatestButton = names(await tree()).some(name => name.includes("Scroll to latest"));
    if (process.env.KODEX_HISTORY_MATRIX_ONLY !== "1") {
      await wait(tree, nodes => !names(nodes).some(name => name.includes("Scroll to latest")),
        "wheel return to latest must restore follow (Latest button disappears)", 5000);
    }
    for (const [width, height] of [[640, 480], [1280, 1000]]) {
      await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
      assertBottom(await sample(`13-viewport-${width}x${height}`), "HISTORY_MANY_LATEST");
    }
    await send("Page.reload");
    await waitConnected();
    await openSession("History many fixture");
    await waitText("HISTORY_MANY_LATEST");
    assertBottom(await sample("14-reload-reimport-existing-session"), "HISTORY_MANY_LATEST");
    await openSession("History disclosure fixture");
    await waitText("HISTORY_TOOL_LATEST");
    assertBottom(await sample("15-tool-collapsed"), "HISTORY_TOOL_LATEST");
    const group = (await tree()).find(n => n.role?.value === "button" &&
      n.name?.value?.startsWith("History group:"));
    if (group) await pointerClick(group.name.value);
    const tool = await wait(tree, nodes => nodes.some(n => n.role?.value === "button" &&
      /^(Expand History item:|History tool:)/.test(n.name?.value ?? "")), "history tool disclosure");
    await pointerClick(tool.find(n => n.role?.value === "button" &&
      /^(Expand History item:|History tool:)/.test(n.name?.value ?? "")).name.value);
    await sample("16-tool-expanded");
    const outputDisclosure = (await tree()).find(n => n.role?.value === "button" &&
      n.name?.value === "History disclosure: Output");
    if (outputDisclosure) await pointerClick(outputDisclosure.name.value);
    await waitText("HISTORY_TOOL_OUTPUT_99");
    assertBottom(await sample("17-tool-long-output-expanded"), "HISTORY_TOOL_LATEST");
    assert.deepEqual(errors, []);
    outcome = process.env.KODEX_HISTORY_MATRIX_ONLY === "1" ? "matrix-capture-not-an-acceptance-pass" :
      "geometry-gates-passed-awaiting-trajectory-review";
  }
  }
} catch (error) {
  outcome = `failed: ${error.message}`;
  if (sessionId && socket?.readyState === WebSocket.OPEN) await sample("failure").catch(() => {});
  throw error;
} finally {
  await writeFile(join(output, "history-result.json"), JSON.stringify({
    outcome, samples: samples.map(s => s.label), inputs, errors, rpcFrames, measurements,
    socketPaths: events.filter(e => e.method === "Network.webSocketCreated").map(e => {
      const url = new URL(e.params.url);
      assert.equal(url.port, String(connection.port));
      return url.pathname;
    }),
    note: "AX box absence is recorded separately; screenshot OCR is actual raster geometry, not AX layout.",
  }, null, 2));
  await writeFile(join(output, "chrome.stderr.log"), stderr);
  if (socket) socket.close();
  chrome.kill("SIGTERM");
  await Promise.race([exited, delay(5000)]);
  if (chrome.exitCode === null) { chrome.kill("SIGKILL"); await exited; }
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

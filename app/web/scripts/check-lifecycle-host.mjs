import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

// Diagnostic only: no Wasm, backend, credentials or product callbacks.
// Compare forced freeze/resume, tab activation and headless focus emulation.
const profile = await mkdtemp(join(tmpdir(), "kodex-995-freeze-control-"));
const chrome = spawn(process.env.CHROME_BIN ?? "/usr/bin/google-chrome", [
  "--headless=new", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update",
  "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--no-first-run", "--no-default-browser-check", "--window-size=1280,1000",
  "--force-renderer-accessibility", "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "about:blank",
], { stdio: "ignore" });
const exit = new Promise(done => chrome.once("exit", done));
const pending = new Map();
let socket, sequence = 0, sessionId;
function send(method, params = {}, session = sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 10_000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params, ...(session ? { sessionId: session } : {}) }));
  });
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, userGesture: true });
  assert.equal(result.exceptionDetails, undefined);
  return result.result.value;
}
async function snapshot(phase) {
  console.log(JSON.stringify({ phase, state: await evaluate(`({
    visibility:document.visibilityState, focused:document.hasFocus(),
    frames:globalThis.framesCount, clicks:globalThis.clicks,
    documentText:document.body.innerText
  })`) }));
}
try {
  let active = "";
  for (let attempt = 0; attempt < 100 && !active.includes("\n"); attempt++) {
    active = await readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => "");
    if (!active) await delay(100);
  }
  assert.ok(active);
  const [port, path] = active.trim().split("\n");
  socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id); clearTimeout(request.timer);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  await new Promise(done => socket.addEventListener("open", done, { once: true }));
  const { browserContextId } = await send("Target.createBrowserContext", {}, null);
  const { targetId } = await send("Target.createTarget", { url: "about:blank", browserContextId }, null);
  ({ sessionId } = await send("Target.attachToTarget", { targetId, flatten: true }, null));
  await send("Page.enable");
  await send("Runtime.enable");
  await evaluate(`(() => {
    globalThis.framesCount = 0; globalThis.clicks = 0;
    document.body.innerHTML = '<button onclick="document.querySelector(\\'span\\').textContent=++globalThis.clicks">Count</button><span>0</span>';
    function tick() { globalThis.framesCount++; requestAnimationFrame(tick); }
    requestAnimationFrame(tick);
  })()`);
  await delay(500); await snapshot("initial");
  await send("Page.setWebLifecycleState", { state: "frozen" });
  await delay(1_000); await snapshot("frozen");
  await send("Page.setWebLifecycleState", { state: "active" });
  await delay(500); await snapshot("active");
  await send("Page.bringToFront");
  await delay(500); await snapshot("bring-to-front");
  await evaluate("document.querySelector('button').click()");
  await delay(500); await snapshot("ordinary-dom-click");
  await send("Emulation.setFocusEmulationEnabled", { enabled: true });
  await delay(500); await snapshot("focus-emulation");
} finally {
  socket?.close();
  chrome.kill("SIGTERM");
  await Promise.race([exit, delay(5_000)]);
  if (chrome.exitCode === null && chrome.signalCode === null) { chrome.kill("SIGKILL"); await exit; }
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

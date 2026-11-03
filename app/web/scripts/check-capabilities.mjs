import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { createServer as createTcpServer } from "node:net";
import { chmod, mkdtemp, mkdir, readFile, readdir, realpath, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const here = dirname(fileURLToPath(import.meta.url));
let stopping = false;
let interruptOwnedChildren = () => {};
export const secretMarkers = [
  "capability-openai-id-private", "capability-openai-access-private", "capability-openai-refresh-private",
  "capability-mcp-access-private", "capability-mcp-refresh-private",
];

function controllerRequest(action, args = {}) {
  assert.equal(typeof process.send, "function", "Capability checks require their private owning controller.");
  return new Promise((done, fail) => {
    const id = randomBytes(8).toString("hex");
    const timeout = setTimeout(() => { process.off("message", receive); fail(new Error("Private controller timeout")); },
      action === "openai-ports-free" ? 75_000 : 30_000);
    function receive(message) {
      if (message.id !== id) return;
      clearTimeout(timeout);
      process.off("message", receive);
      if (message.ok) done(message.value);
      else fail(new Error("Private capability boundary rejected the check; no sensitive diagnostics retained."));
    }
    process.on("message", receive);
    process.send({ id, action, ...args });
  });
}

export async function freshPairingOffer() { return controllerRequest("pair"); }

/** All interactions target the shipped Wasm and its shared ViewModels, never a fixture renderer. */
export async function probeCapabilities(h) {
  const { click, button, tree, text, wait, waitText, key, editor, evaluate, send, measurements } = h;
  const launch = process.env.KODEX_CAPABILITY_LAUNCH_DIRECTORY;
  assert.ok(launch?.startsWith(process.env.KODEX_CAPABILITY_SANDBOX + sep));
  const settings = async page => { await click(`Settings page ${page}`); await waitText(`Settings content ${page}`); };
  async function input(label, value) {
    await wait(editor, inputs => inputs.some(item => item.label === label), "capability editor mounted");
    await evaluate(`(() => {
      function visit(root) {
        for (const e of root.querySelectorAll('*')) {
          if (e.tagName === 'TEXTAREA' && e.getAttribute('aria-label') === ${JSON.stringify(label)}) {
            e.focus(); e.value = ${JSON.stringify(value)}; e.setSelectionRange(e.value.length, e.value.length);
            e.dispatchEvent(new Event('input', { bubbles: true })); return true;
          }
          if (e.shadowRoot && visit(e.shadowRoot)) return true;
        }
        return false;
      }
      if (!visit(document)) throw new Error('Missing capability field');
    })()`);
    await wait(editor, inputs => inputs.some(item => item.label === label && item.value === value), "capability draft updated");
  }
  async function choice(prefix, selected) {
    const trigger = item => item.role?.value === "button" && item.name?.value?.startsWith(prefix + ": ");
    const nodes = await wait(tree, values => values.some(trigger), "production choice trigger is projected");
    const node = nodes.find(trigger);
    assert.ok(node, "Production choice must exist.");
    if (node.name.value === `${prefix}: ${selected}`) return;
    await click(node.name.value);
    // Dropdown items are buttons, not ARIA tabs. Verify the changed trigger and dismissed
    // option list instead of requiring an inapplicable AX selected property.
    const option = node => node.name?.value === `${prefix}: ${selected}` &&
      node.role?.value === "button" && node.backendDOMNodeId;
    const item = await wait(tree, nodes => nodes.some(option), "production choice option semantics");
    const target = item.find(option);
    const { object } = await send("DOM.resolveNode", { backendNodeId: target.backendDOMNodeId });
    try {
      await send("Runtime.callFunctionOn", { objectId: object.objectId,
        functionDeclaration: "function(){this.click()}", userGesture: true });
    } finally { await send("Runtime.releaseObject", { objectId: object.objectId }); }
    await wait(tree, nodes => nodes.some(option) &&
      !nodes.some(item => item.role?.value === "button" && item.name?.value === node.name.value),
    "choice commits its changed value and removes the old option list");
  }
  async function toggle(name, checked) {
    const nodes = await wait(tree, values => values.some(node => ["checkbox", "switch"].includes(node.role?.value) &&
      node.name?.value === name), "production toggle is projected");
    const control = nodes.find(node => ["checkbox", "switch"].includes(node.role?.value) &&
      node.name?.value === name);
    assert.ok(control, "Production toggle must exist.");
    const current = control.properties?.find(property => property.name === "checked")?.value.value;
    assert.ok([true, false, "true", "false"].includes(current), "Toggle accessibility must expose its actual state.");
    if ((current === true || current === "true") !== checked) await click(name);
  }
  const notDisabled = node => !node.properties?.some(p => p.name === "disabled" && p.value.value);
  async function hookAdd(sectionIndex) {
    const nodes = await wait(tree, values => values.filter(node =>
      node.role?.value === "button" && node.name?.value === "Add").length === 2,
    "independent Backend notifications and Frontend errors actions");
    const node = nodes.filter(node => node.role?.value === "button" && node.name?.value === "Add")[sectionIndex];
    assert.ok(node?.backendDOMNodeId && notDisabled(node));
    const { object } = await send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId });
    try {
      await send("Runtime.callFunctionOn", { objectId: object.objectId,
        functionDeclaration: "function(){this.click()}", userGesture: true });
    } finally { await send("Runtime.releaseObject", { objectId: object.objectId }); }
    await waitText("Add Hook");
  }
  await click("Settings");
  measurements.capabilityStage = "directory-picker";
  await settings("CurrentSession");
  assert.ok(notDisabled(await button("Browse")));
  await click("Browse");
  await waitText("Select directory");
  await waitText(launch);
  await button("Alpha/");
  await button("beta/");
  await button("目录 space/");
  assert.ok(!text(await tree()).includes("not-a-directory/"), "Backend picker excludes files.");
  await click("Alpha/");
  await button("nested/");
  await click("Cancel");
  await wait(tree, nodes => !text(nodes).includes("Select directory"), "picker cancelled without mutation");
  await click("Browse");
  await waitText(launch);
  await click("Alpha/");
  await button("nested/");
  await click("Select");
  measurements.capabilityStage = "directory-selection-completion";
  await wait(tree, nodes => !text(nodes).includes("Select directory"), "validated directory selection closes its picker");
  await waitText(join(launch, "Alpha"));
  await key("Escape", 27);
  await wait(editor, inputs => inputs.some(item => item.label === "Message" && item.active), "settings dismissed");
  await wait(tree, nodes => !nodes.some(node => node.name?.value === "Settings content CurrentSession"),
    "old settings content is removed before reopening");
  await click("Settings");
  measurements.capabilityStage = "directory-selection-persisted";
  await settings("CurrentSession");
  await waitText(join(launch, "Alpha"));
  measurements.capabilityStage = "directory-restore";
  // Restore this Session only. The companion's immutable initial launch base remains launch.
  assert.ok(notDisabled(await button("Browse")));
  measurements.directoryRestoreClick = await click("Browse");
  await waitText("Select directory");
  measurements.capabilityStage = "directory-restore-listing";
  await button("nested/");
  await click("..");
  await button("Alpha/");
  await click("Select");
  await wait(tree, nodes => !text(nodes).includes("Select directory"), "restored directory selection closes its picker");
  await waitText(launch);
  measurements.backendDirectoryPicker = { childListing: true, cancel: true, selectedAndReopened: true };

  await settings("ContextSources");
  measurements.capabilityStage = "context-sources";
  for (const candidate of [join(launch, "context-source"), `${launch}/Alpha/../context-source/`]) {
    await click("Add source");
    await input("Context source path", candidate);
    await click("Add");
    await wait(editor, inputs => !inputs.some(item => item.label === "Context source path"), "context accepted by shared VM");
    await wait(tree, nodes => nodes.filter(node => node.role?.value === "StaticText" &&
      node.name?.value === join(launch, "context-source")).length === 1,
    "accepted context write is observed exactly once before the next input");
    await controllerRequest("context-persisted");
  }
  const contextNodes = await tree();
  assert.equal(contextNodes.filter(node => node.role?.value === "StaticText" &&
    node.name?.value === join(launch, "context-source")).length, 1, "Lexical duplicate re-enables, never appends.");
  await controllerRequest("context-persisted");
  measurements.contextPath = { saved: true, lexicalDuplicate: true, backendPersistence: true };

  await settings("OpenAi");
  for (const source of ["Kodex", "Codex"]) {
    measurements.capabilityStage = `openai-${source}`;
    await choice("Authentication", source);
    await waitText(`${source} credentials`);
    assert.ok(notDisabled(await button("Sign in")));
    await controllerRequest("openai-ports-free");
    await click("Sign in");
    await click("Open browser");
    await waitText("Open this address in your browser:");
    const nodes = await tree();
    const urls = nodes.filter(node => node.role?.value === "StaticText" &&
      node.name?.value?.startsWith(process.env.KODEX_CAPABILITY_PROVIDER_ORIGIN + "/openai-authorize?"));
    assert.equal(urls.length, 1, "Manual authorization must expose exactly the active provider URL.");
    await controllerRequest("authorize", { provider: "openai", url: urls[0].name.value });
    await waitText("Sign-in complete");
    await key("Escape", 27);
    await button("Sign in again");
    await controllerRequest("openai-persisted", { source });
  }
  measurements.openAiLogin = { sources: ["Kodex", "Codex"], realNativeCallback: true, pkce: true, persisted: true };

  await settings("Mcp");
  measurements.capabilityStage = "mcp-configuration";
  await click("Add");
  await input("Server name", "capability-mcp");
  await choice("Transport", "HTTP");
  await input("URL", process.env.KODEX_CAPABILITY_PROVIDER_ORIGIN + "/mcp");
  await toggle("OAuth", true);
  await input("OAuth redirect URI", process.env.KODEX_CAPABILITY_MCP_REDIRECT);
  await click("Save");
  await wait(editor, inputs => !inputs.some(item => item.label === "Server name"), "MCP configuration saved");
  const mcp = await wait(tree, nodes => nodes.some(node => node.role?.value === "button" &&
    node.name?.value?.startsWith("capability-mcp · ")), "MCP list entry");
  await click(mcp.find(node => node.role?.value === "button" && node.name?.value?.startsWith("capability-mcp · ")).name.value);
  measurements.capabilityStage = "mcp-login";
  await click("Log in");
  await waitText("Open this address in your browser:");
  const nodes = await tree();
  const urls = nodes.filter(node => node.role?.value === "StaticText" &&
    node.name?.value?.startsWith(process.env.KODEX_CAPABILITY_PROVIDER_ORIGIN + "/authorize?"));
  assert.equal(urls.length, 1, "Manual MCP authorization must expose exactly the active provider URL.");
  await controllerRequest("authorize", { provider: "mcp", url: urls[0].name.value });
  await waitText("Authentication: Authorized");
  await button("Log out");
  await controllerRequest("mcp-persisted");
  await key("Escape", 27);
  measurements.mcpLogin = { discovery: true, registration: true, pkce: true, realNativeCallback: true, persisted: true };

  await settings("Hooks");
  measurements.capabilityStage = "frontend-error-hook-configuration";
  await hookAdd(1);
  await input("Name", "capability-observe-error");
  await toggle("Unhandled error", true);
  assert.ok(!(await tree()).some(node => ["checkbox", "switch"].includes(node.role?.value) &&
    ["Assistant message", "Request user input", "Suggest subagent"].includes(node.name?.value)),
    "Frontend errors must not offer backend Stop notification types.");
  await input("Command", process.env.KODEX_CAPABILITY_ERROR_COMMAND);
  await click("Save");
  await waitText("capability-observe-error Unhandled error");
  await hookAdd(0);
  measurements.capabilityStage = "backend-hook-configuration";
  await input("Name", "capability-stop-runner");
  for (const type of ["Assistant message", "Request user input", "Suggest subagent", "Unhandled error"]) {
    await toggle(type, type === "Assistant message");
  }
  await input("Command", process.env.KODEX_CAPABILITY_STOP_COMMAND);
  await click("Save");
  await waitText("capability-stop-runner Assistant message");
  await controllerRequest("hooks-persisted");
  await key("Escape", 27);
  await wait(editor, inputs => inputs.some(item => item.label === "Message" && item.active), "shared Composer after Hook save");
  await input("Message", "capability real backend Hook runner");
  measurements.capabilityStage = "backend-hook-turn";
  await key("Enter", 13);
  await controllerRequest("stop-ran");
  await wait(editor, inputs => inputs.some(item => item.label === "Message" && item.value === "" && item.active), "Hook turn finished");
  measurements.capabilityStage = "frontend-error-hook-execution";
  await controllerRequest("error-marker-empty");
  await wait(tree, nodes => nodes.some(node => node.role?.value === "button" &&
    node.name?.value === "Compact" && notDisabled(node)), "Compact is ready after the fake turn");
  await click("Compact");
  await controllerRequest("error-ran");
  measurements.hooks = {
    errorConfigurationPersisted: true, actualBackendStopRunner: true,
    frontendObserveErrorRunner: "Compact fake-provider failure reached the real frontend error Hook exactly once",
  };
  await controllerRequest("provider-evidence");
  measurements.capabilityStage = "completed";
}

async function listen(server, port = 0) {
  await new Promise((done, fail) => { server.once("error", fail); server.listen({ host: "127.0.0.1", port, exclusive: true }, done); });
  return server.address().port;
}
async function close(server) {
  if (!server?.listening) return;
  server.closeAllConnections?.();
  await new Promise(done => server.close(done));
}
async function requireOpenAiPortsFree() {
  const guards = [];
  try {
    for (const port of [1455, 1457]) {
      const guard = createTcpServer();
      guards.push(guard);
      try { await listen(guard, port); }
      catch { throw new Error(`BLOCKED: callback port ${port} is occupied; do not stop its owner.`); }
    }
  } finally { for (const guard of guards) await close(guard); }
}
async function waitForOpenAiPortsAfterOwnCallback() {
  const deadline = Date.now() + 70_000;
  while (true) {
    try { await requireOpenAiPortsFree(); return; }
    catch (failure) {
      if (stopping || Date.now() >= deadline ||
          !/^BLOCKED: callback port (1455|1457) is occupied; do not stop its owner\.$/.test(failure.message)) throw failure;
      await delay(500);
    }
  }
}
async function waitOwned(read, matches, label, ms = 30_000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    assert.equal(stopping, false, "Parent stopped this owned validation window.");
    const value = await read();
    if (matches(value)) return value;
    await delay(100);
  }
  throw new Error(label);
}
async function readPrivate(path) {
  const info = await stat(path);
  assert.equal(info.mode & 0o777, 0o600, "Sensitive task file permissions.");
  assert.ok(info.isFile() && info.size <= 1_048_576, "Bounded sensitive task file.");
  return readFile(path, "utf8");
}
async function body(request) {
  let text = "";
  for await (const bytes of request) { text += bytes; assert.ok(Buffer.byteLength(text) <= 65_536); }
  return text;
}
function fakeProvider() {
  const codes = new Map();
  const counts = { openai: 0, mcp: 0, registration: 0 };
  let origin;
  const server = createServer((req, res) => {
    Promise.resolve().then(async () => {
      assert.equal(req.headers.host, new URL(origin).host);
      const url = new URL(req.url, origin);
      const json = (value, status = 200) => {
        res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify(value));
      };
      if (req.method === "GET" && ["/openai-authorize", "/authorize"].includes(url.pathname)) {
        const p = url.searchParams;
        assert.equal(p.getAll("state").length, 1);
        assert.ok(p.get("state"));
        assert.equal(p.get("response_type"), "code");
        assert.equal(p.get("code_challenge_method"), "S256");
        assert.match(p.get("code_challenge"), /^[A-Za-z0-9_-]{43}$/);
        const callback = new URL(p.get("redirect_uri"));
        assert.equal(callback.protocol, "http:");
        assert.ok(["127.0.0.1", "localhost"].includes(callback.hostname));
        assert.ok(Number(callback.port) > 0);
        const provider = url.pathname === "/authorize" ? "mcp" : "openai";
        assert.equal(p.get("client_id"), provider === "mcp" ? "capability-mcp-client" : "capability-openai");
        if (provider === "mcp") {
          assert.equal(p.get("resource"), origin + "/mcp");
          assert.equal(p.get("scope"), "tools.read");
        }
        const code = randomBytes(24).toString("hex");
        codes.set(code, { provider, redirect: p.get("redirect_uri"), challenge: p.get("code_challenge") });
        callback.searchParams.set("code", code);
        callback.searchParams.set("state", p.get("state"));
        res.writeHead(302, { Location: callback.href, "Cache-Control": "no-store" });
        res.end("Task-only authorization page");
      } else if (req.method === "POST" && ["/token", "/openai-token"].includes(url.pathname)) {
        const p = new URLSearchParams(await body(req));
        const code = codes.get(p.get("code"));
        assert.ok(code, "Unknown/replayed fake authorization code.");
        codes.delete(p.get("code"));
        assert.equal(p.get("grant_type"), "authorization_code");
        assert.equal(p.get("redirect_uri"), code.redirect);
        assert.equal(createHash("sha256").update(p.get("code_verifier")).digest("base64url"), code.challenge);
        assert.equal(url.pathname, code.provider === "mcp" ? "/token" : "/openai-token");
        assert.equal(p.get("client_id"), code.provider === "mcp" ? "capability-mcp-client" : "capability-openai");
        if (code.provider === "mcp") assert.equal(p.get("resource"), origin + "/mcp");
        counts[code.provider]++;
        json(code.provider === "mcp" ? {
          access_token: secretMarkers[3], refresh_token: secretMarkers[4], token_type: "Bearer", expires_in: 3600,
        } : {
          id_token: secretMarkers[0], access_token: secretMarkers[1], refresh_token: secretMarkers[2],
          account_id: "capability-account", expires_in: 3600,
        });
      } else if (req.method === "GET" && url.pathname === "/resource-metadata") {
        json({ resource: origin + "/mcp", authorization_servers: [origin], scopes_supported: ["tools.read"] });
      } else if (req.method === "GET" && url.pathname === "/.well-known/oauth-authorization-server") {
        json({ issuer: origin, authorization_endpoint: origin + "/authorize", token_endpoint: origin + "/token",
          registration_endpoint: origin + "/register", token_endpoint_auth_methods_supported: ["none"],
          code_challenge_methods_supported: ["S256"] });
      } else if (req.method === "POST" && url.pathname === "/register") {
        const registration = JSON.parse(await body(req));
        assert.equal(registration.client_name, "Kodex");
        assert.equal(registration.token_endpoint_auth_method, "none");
        counts.registration++;
        json({ client_id: "capability-mcp-client", token_endpoint_auth_method: "none" }, 201);
      } else if (req.method === "POST" && url.pathname === "/mcp") {
        if (req.headers.authorization !== `Bearer ${secretMarkers[3]}`) {
          res.writeHead(401, { "WWW-Authenticate": `Bearer resource_metadata="${origin}/resource-metadata", scope="tools.read"` });
          res.end();
          return;
        }
        const message = JSON.parse(await body(req));
        if (message.id === undefined) { res.writeHead(202); res.end(); return; }
        const result = message.method === "initialize" ? {
          protocolVersion: message.params.protocolVersion, capabilities: { tools: {}, resources: {}, prompts: {} },
          serverInfo: { name: "task-capability-provider", version: "1" },
        } : message.method === "tools/list" ? { tools: [] } : message.method === "resources/list" ? { resources: [] } :
          message.method === "resources/templates/list" ? { resourceTemplates: [] } : { prompts: [] };
        json({ jsonrpc: "2.0", id: message.id, result });
      } else { res.writeHead(405); res.end(); }
    }).catch(() => { res.writeHead(400); res.end("Task provider rejected request"); });
  });
  return { server, counts, setOrigin(value) { origin = value; } };
}

async function stopChild(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const exited = new Promise(done => child.once("close", done));
  child.kill("SIGTERM");
  await Promise.race([exited, delay(10_000)]);
  if (child.exitCode === null && child.signalCode === null) {
    child.kill("SIGKILL");
    assert.ok(await Promise.race([exited.then(() => true), delay(5_000).then(() => false)]),
      "Owned child did not close after termination.");
  }
}

async function runEntry(entry, options) {
  const sandbox = await realpath(await mkdtemp("/tmp/kodex-capabilities-"));
  await chmod(sandbox, 0o700);
  let fixture, fixtureClosed, driver, driverClosed, driverStatus, provider, assets;
  let guardReady = false, primary;
  interruptOwnedChildren = () => {
    if (driver?.exitCode === null && driver?.signalCode === null) driver.kill("SIGTERM");
    if (fixture?.stdin?.writable) fixture.stdin.end("\n");
  };
  try {
    const osHome = join(sandbox, "os-home"), tmp = join(sandbox, "tmp"), cwd = join(sandbox, "native-launch");
    const bin = join(sandbox, "bin");
    for (const dir of [osHome, tmp, cwd, bin]) await mkdir(dir, { mode: 0o700 });
    provider = fakeProvider();
    const providerOrigin = `http://127.0.0.1:${await listen(provider.server)}`;
    provider.setOrigin(providerOrigin);
    const mcpGuard = createTcpServer();
    let mcpPort;
    try { mcpPort = await listen(mcpGuard); }
    finally { await close(mcpGuard); }
    // The Hook contains no user script, inherited shell setup, external command or auth material.
    const stopMarker = join(sandbox, "stop-hook.private"), errorMarker = join(sandbox, "error-hook.private");
    const stopCommand = `umask 077; printf 'stop-runner\\n' >> '${stopMarker}'`;
    const errorCommand = `umask 077; printf 'frontend-error\\n' >> '${errorMarker}'`;
    const isolatedEnv = {
      PATH: `${bin}:/usr/bin:/bin`, HOME: osHome, USERPROFILE: osHome, XDG_CONFIG_HOME: join(osHome, ".config"),
      SHELL: "/bin/sh", TMPDIR: tmp, LANG: "C.UTF-8",
      KODEX_CHECK_CAPABILITIES: "1", KODEX_CAPABILITY_PROVIDER_ORIGIN: providerOrigin,
      KODEX_CAPABILITY_SANDBOX: sandbox,
      KODEX_CAPABILITY_LAUNCH_DIRECTORY: cwd,
      KODEX_CAPABILITY_MCP_REDIRECT: `http://127.0.0.1:${mcpPort}/callback`,
      KODEX_BROWSER_FIXTURE_WEBSITE: options.website,
      KODEX_BROWSER_FIXTURE_PAIRING_ORIGIN: "http://127.0.0.1:18795",
      KODEX_BROWSER_FIXTURE_TTL_SECONDS: "600",
      KODEX_BROWSER_FIXTURE_DELAY_MS: "5000", KODEX_CHECK_RUNNING_SPINNERS: "1",
    };
    assets = createServer((req, res) => {
      Promise.resolve().then(async () => {
        assert.equal(req.headers.host, "127.0.0.1:18795");
        const requested = new URL(req.url, "http://127.0.0.1:18795");
        assert.equal(requested.search, "");
        const relative = requested.pathname === "/" ? "index.html" : decodeURIComponent(requested.pathname.slice(1));
        const path = await realpath(resolve(options.website, relative));
        assert.ok(path.startsWith(options.website + sep));
        const bytes = await readFile(path);
        const type = path.endsWith(".wasm") ? "application/wasm" : path.endsWith(".js") || path.endsWith(".mjs") ?
          "application/javascript" : path.endsWith(".html") ? "text/html" : path.endsWith(".ttf") ? "font/ttf" : "application/octet-stream";
        res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" }); res.end(bytes);
      }).catch(() => { res.writeHead(404); res.end(); });
    });
    try { await listen(assets, 18795); }
    catch { throw new Error("BLOCKED: static port 18795 is occupied; do not stop its owner."); }
    fixture = spawn(options.fixture, [], { cwd, env: isolatedEnv, stdio: ["pipe", "pipe", "pipe"] });
    let fixtureOutput = "";
    fixture.stdout.on("data", bytes => { fixtureOutput = (fixtureOutput + bytes).slice(-65_536); });
    fixture.stderr.resume(); // Sensitive fixture diagnostics never printed or archived.
    fixtureClosed = new Promise(done => fixture.once("close", done));
    let spawnFailed = false;
    fixture.once("error", () => { spawnFailed = true; });
    const ready = await waitOwned(async () => {
      assert.ok(!spawnFailed && fixture.exitCode === null && fixture.signalCode === null, "Fixture did not stay alive.");
      return fixtureOutput.match(/PRODUCT_BROWSER_READY ([^\n;]+);/);
    }, Boolean, "Production fixture readiness failed");
    const recordPath = ready[1];
    assert.ok(recordPath.startsWith(tmp + "/kodex-product-browser-"));
    const home = dirname(dirname(dirname(recordPath)));
    const launch = cwd;
    const privateRecord = JSON.parse(await readPrivate(recordPath));
    assert.ok(Number.isInteger(privateRecord.port) && privateRecord.port > 0);
    const launchFile = await waitOwned(async () => fixtureOutput.match(/PRODUCT_FRONTEND_READY ([^\n]+)/)?.[1],
      Boolean, "Automatic frontend readiness failed");
    assert.equal(launchFile, join(home, "frontend-launch.private"));
  let pairingSerial = Promise.resolve();
    let ownOpenAiCallbackCompleted = false;
    async function privateAction(message) {
      switch (message.action) {
        case "openai-ports-free":
          if (ownOpenAiCallbackCompleted) await waitForOpenAiPortsAfterOwnCallback();
          else await requireOpenAiPortsFree();
          return true;
        case "pair": {
          let offer;
          pairingSerial = pairingSerial.then(async () => {
            fixtureOutput = fixtureOutput.replace(/PRODUCT_PAIRING_READY [^\n]+\n/g, "");
            fixture.stdin.write("pair\n");
            offer = await waitOwned(async () => fixtureOutput.match(/PRODUCT_PAIRING_READY ([^\n]+)/)?.[1],
              Boolean, "Scoped pairing not ready");
            assert.equal(offer, join(home, "frontend-pairing.private"));
            const parsed = JSON.parse(await readPrivate(offer));
            assert.ok(!JSON.stringify(parsed).includes(privateRecord.password));
          });
          await pairingSerial;
          return offer;
        }
        case "authorize": {
          const captured = message.url;
          assert.ok(typeof captured === "string" && captured.length <= 16_384);
          const url = new URL(captured);
          assert.equal(url.origin, providerOrigin);
          assert.equal(url.pathname, message.provider === "mcp" ? "/authorize" : "/openai-authorize");
          const page = await fetch(captured, { redirect: "manual", signal: AbortSignal.timeout(10_000) });
          assert.equal(page.status, 302);
          const callback = new URL(page.headers.get("location"));
          assert.equal(callback.origin + callback.pathname, url.searchParams.get("redirect_uri"));
          const wrongState = new URL(callback);
          wrongState.searchParams.set("state", randomBytes(24).toString("hex"));
          assert.equal((await fetch(wrongState, { redirect: "manual", signal: AbortSignal.timeout(5_000) })).status, 400);
          const duplicateState = new URL(callback);
          duplicateState.searchParams.append("state", "duplicate-task-state");
          assert.equal((await fetch(duplicateState, { redirect: "manual", signal: AbortSignal.timeout(5_000) })).status, 400);
          const accepted = await fetch(callback, { redirect: "manual", signal: AbortSignal.timeout(10_000) });
          assert.equal(accepted.status, 200, "Actual Native callback must accept the bound code/state.");
          if (message.provider === "openai") ownOpenAiCallbackCompleted = true;
          const replay = await fetch(callback, { redirect: "manual", signal: AbortSignal.timeout(5_000) }).catch(() => null);
          assert.ok(!replay || replay.status !== 200, "Callback must not be accepted twice.");
          return true;
        }
        case "openai-persisted": {
          assert.ok(["Kodex", "Codex"].includes(message.source));
          const path = message.source === "Kodex" ? join(home, "auth.yml") : join(home, "codex", "auth.json");
          await waitOwned(async () => readPrivate(path).catch(() => ""), value => value.includes(secretMarkers[1]) &&
            value.includes(secretMarkers[2]), "Selected OpenAI destination not persisted");
          return true;
        }
        case "context-persisted": {
          await waitOwned(async () => readFile(join(home, "settings.yml"), "utf8"),
            settings => settings.split(join(launch, "context-source")).length - 1 === 1, "Context source not persisted exactly once");
          return true;
        }
        case "hooks-persisted": {
          function section(settings, name) {
            const lines = settings.split("\n");
            const start = lines.findIndex(line => line.startsWith(name + ":"));
            if (start < 0) return "";
            let end = start + 1;
            while (end < lines.length && (!lines[end].trim() || /^\s/.test(lines[end]))) end++;
            return lines.slice(start, end).join("\n");
          }
          await waitOwned(async () => readFile(join(home, "settings.yml"), "utf8"), settings => {
            const frontend = section(settings, "frontend"), backend = section(settings, "backend");
            return frontend.includes("capability-observe-error") && frontend.includes("stop_unhandled_error") &&
              !backend.includes("capability-observe-error") &&
              backend.includes("capability-stop-runner") && backend.includes("stop_assistant_message") &&
              !frontend.includes("capability-stop-runner");
          }, "Backend and frontend Hook configurations must persist to distinct owners");
          return true;
        }
        case "stop-ran":
          await waitOwned(async () => readPrivate(stopMarker).catch(() => ""), value => value === "stop-runner\n",
            "Configured backend Hook runner did not execute exactly once");
          return true;
        case "error-marker-empty": {
          try {
            await stat(errorMarker);
            throw new Error("Frontend error marker existed before Compact.");
          } catch (failure) {
            if (failure.code !== "ENOENT") throw failure;
          }
          return true;
        }
        case "error-ran":
          await waitOwned(async () => readPrivate(errorMarker).catch(failure => {
            if (failure.code === "ENOENT") return "";
            throw failure;
          }), value => value === "frontend-error\n", "Frontend error Hook did not run exactly once");
          await delay(1_000);
          assert.equal(await readPrivate(errorMarker), "frontend-error\n", "Frontend error Hook must not replay.");
          return true;
        case "mcp-persisted": {
          // Search only our new private Home, never ~/.codex or other user configuration.
          let found = false;
          async function visit(dir, depth = 0) {
            assert.ok(depth <= 8);
            for (const item of await readdir(dir, { withFileTypes: true })) {
              if (item.isSymbolicLink() || item.name === ".locks" || item.name === "logs") continue;
              const path = join(dir, item.name);
              if (item.isDirectory()) await visit(path, depth + 1);
              else if (item.isFile() && (await stat(path)).size <= 1_048_576) {
                const value = await readFile(path, "utf8");
                if (value.includes(secretMarkers[3]) && value.includes(secretMarkers[4])) {
                  assert.equal((await stat(path)).mode & 0o777, 0o600); found = true;
                }
              }
            }
          }
          await waitOwned(async () => { await visit(home); return found; }, Boolean, "MCP tokens not persisted");
          return true;
        }
        case "provider-evidence":
          assert.deepEqual(provider.counts, { openai: 2, mcp: 1, registration: 1 });
          return true;
        default: throw new Error("Unrecognized private probe request.");
      }
    }
    driver = spawn(process.execPath, [join(here, "owned-product-group.mjs"), process.execPath,
      join(here, "check-product.mjs"), options.dependencies, join(options.output, entry)], {
      cwd, env: {
        ...isolatedEnv, CHROME_BIN: options.chrome, KODEX_BROWSER_RECORD: recordPath, KODEX_BROWSER_LAUNCH_FILE: launchFile,
        KODEX_CAPABILITY_ENTRY: entry, KODEX_CAPABILITY_SANDBOX: sandbox, KODEX_CAPABILITY_LAUNCH_DIRECTORY: launch,
        KODEX_CAPABILITY_STOP_COMMAND: stopCommand, KODEX_CAPABILITY_ERROR_COMMAND: errorCommand,
      }, detached: true, stdio: ["ignore", "ignore", "pipe", "ipc"],
    });
    driver.stderr.resume();
    driver.on("message", envelope => {
      if (envelope.rowGroup === "status") { driverStatus = envelope; return; }
      if (envelope.rowGroup === "kill-ready") {
        guardReady = true;
        if (driver.connected) driver.send({ rowGroup: "kill-now" }, () => {});
        return;
      }
      if (envelope.rowGroup !== "forward") return;
      const message = envelope.value;
      privateAction(message).then(
        value => { if (driver.connected) driver.send({ rowGroup: "forward", value: { id: message.id, ok: true, value } }, () => {}); },
        failure => {
          // Preserve only a stage and exception type; provider URLs and callback values stay private.
          console.error(`Private probe ${message.action} failed (${failure?.name ?? "Error"}).`);
          if (driver.connected) driver.send({ rowGroup: "forward", value: { id: message.id, ok: false } }, () => {});
        },
      );
    });
    driverClosed = new Promise(done => {
      driver.once("error", () => done({ error: true }));
      driver.once("close", (code, signal) => done({ code, signal }));
    });
    let deadline;
    const closed = await Promise.race([
      driverClosed,
      new Promise((_, fail) => { deadline = setTimeout(() => fail(new Error("Bounded production capability probe exceeded 8 minutes")), 480_000); }),
    ]).finally(() => clearTimeout(deadline));
    assert.ok(guardReady && closed.signal === "SIGKILL" && driverStatus?.signal === null && driverStatus?.code === 0,
      `Production ${entry} probe or owned-group cleanup failed; inspect only the redacted task result.`);
    fixture.stdin.end("\n");
    await Promise.race([fixtureClosed, delay(15_000)]);
    assert.ok(fixture.exitCode === 0, "Fixture graceful resource cleanup failed.");
    console.log(`PASS ${entry}: production Wasm/backend capabilities, manual authorization and page-originated error Hook.`);
  } catch (failure) {
    primary = failure;
    throw failure;
  } finally {
    interruptOwnedChildren = () => {};
    const cleanupErrors = [];
    async function cleanup(action) { try { await action(); } catch (failure) { cleanupErrors.push(failure); } }
    let driverClean = !driver?.pid;
    await cleanup(async () => {
      if (!driver?.pid) return;
      if (driver.exitCode === null && driver.signalCode === null) driver.kill("SIGTERM");
      const closed = await Promise.race([driverClosed, delay(12_000).then(() => null)]);
      driverClean = !!closed && closed.signal === "SIGKILL" && guardReady;
      assert.ok(driverClean, `Owned driver group not confirmed closed; retained sandbox: ${sandbox}`);
    });
    await cleanup(async () => {
      if (fixture?.stdin?.writable) {
        fixture.stdin.end("\n");
        if (fixtureClosed) await Promise.race([fixtureClosed, delay(15_000)]);
      }
    });
    await cleanup(() => stopChild(fixture));
    await cleanup(() => close(assets));
    await cleanup(() => close(provider?.server));
    if (driverClean && (!fixture?.pid || fixture.exitCode !== null || fixture.signalCode !== null)) {
      await cleanup(() => rm(sandbox, { recursive: true, force: true }));
    }
    if (cleanupErrors.length) {
      if (primary) primary.cleanupErrors = cleanupErrors;
      else throw new AggregateError(cleanupErrors, "Owned product probe cleanup failed.");
    }
  }
}

async function main() {
  assert.ok(["linux", "darwin"].includes(process.platform), "This controller prepares POSIX Native runs only.");
  assert.ok(!process.env.NODE_OPTIONS && process.env.NODE_USE_ENV_PROXY !== "1" &&
    !process.execArgv.includes("--use-env-proxy"), "Start the controller without Node injection or environment proxy modes.");
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.once(signal, () => { stopping = true; interruptOwnedChildren(); });
  }
  assert.equal(process.env.KODEX_CHECK_HISTORY, undefined, "History's strict driver/mode is separate and unchanged.");
  assert.ok(process.env.CHROME_BIN, "Select an explicit headless Chrome binary in the parent-owned window.");
  assert.equal(process.argv.length, 6, "Usage: node check-capabilities.mjs FIXTURE WEBSITE DEPENDENCY_PACKAGE_JSON OUTPUT");
  const options = {
    fixture: await realpath(resolve(process.argv[2])), website: await realpath(resolve(process.argv[3])),
    dependencies: await realpath(resolve(process.argv[4])), output: resolve(process.argv[5]),
    chrome: await realpath(process.env.CHROME_BIN),
  };
  await mkdir(options.output, { recursive: true, mode: 0o700 });
  // Preflight both fixed ports without reuse or interference. Actual production bind still must ACK.
  await requireOpenAiPortsFree();
  for (const entry of ["automatic", "manual"]) {
    assert.equal(stopping, false, "Parent stopped this owned validation window.");
    await runEntry(entry, options);
    if (entry === "automatic") await waitForOpenAiPortsAfterOwnCallback();
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(failure => {
    // Fetch/CDP/launcher errors can carry a state-bearing URL. Never print the raw error or stack.
    console.error(/^BLOCKED: (callback port (1455|1457)|static port 18795) is occupied; do not stop its owner\.$/.test(failure.message) ?
      failure.message : "BLOCKED/FAIL capability probe: ports/resources/build or strict functional assertion; sensitive diagnostics suppressed.");
    process.exitCode = 1;
  });
}

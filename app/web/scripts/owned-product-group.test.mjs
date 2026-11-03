import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

test("owned group forwards IPC and retains its live leader through cleanup", { timeout: 12000 }, async () => {
  const guard = fileURLToPath(new URL("./owned-product-group.mjs", import.meta.url));
  const code = `
    const {spawn} = require('node:child_process');
    spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000)'], {stdio:'ignore'});
    process.send({id:'fake', action:'ping'});
    process.on('message', message => { if (message.ok) process.exit(0); });
  `;
  const group = spawn(process.execPath, [guard, process.execPath, "-e", code], {
    detached: true, stdio: ["ignore", "ignore", "ignore", "ipc"],
  });
  let status, killReady = false, forwarded = false;
  group.on("message", message => {
    if (message.rowGroup === "forward") {
      forwarded = message.value.id === "fake";
      group.send({ rowGroup: "forward", value: { ok: true } });
    } else if (message.rowGroup === "status") status = message;
    else if (message.rowGroup === "kill-ready") {
      killReady = true;
      group.send({ rowGroup: "kill-now" });
    }
  });
  const result = await new Promise((done, fail) => {
    group.once("error", fail);
    group.once("close", (code, signal) => done({ code, signal }));
  });
  assert.equal(forwarded, true);
  assert.deepEqual(status, { rowGroup: "status", code: 0, signal: null });
  assert.equal(killReady, true);
  assert.deepEqual(result, { code: null, signal: "SIGKILL" });
});

test("owned group preserves child failure and still completes its barrier", { timeout: 12000 }, async () => {
  const guard = fileURLToPath(new URL("./owned-product-group.mjs", import.meta.url));
  const group = spawn(process.execPath, [guard, process.execPath, "-e", "process.exit(7)"], {
    detached: true, stdio: ["ignore", "ignore", "ignore", "ipc"],
  });
  let status, ready = false;
  group.on("message", message => {
    if (message.rowGroup === "status") status = message;
    if (message.rowGroup === "kill-ready") { ready = true; group.send({ rowGroup: "kill-now" }); }
  });
  const closed = await new Promise((done, fail) => {
    group.once("error", fail);
    group.once("close", (code, signal) => done({ code, signal }));
  });
  assert.deepEqual(status, { rowGroup: "status", code: 7, signal: null });
  assert.equal(ready, true);
  assert.deepEqual(closed, { code: null, signal: "SIGKILL" });
});

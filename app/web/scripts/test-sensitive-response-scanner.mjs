import assert from "node:assert/strict";
import test from "node:test";
import { sensitiveResponseScanner } from "./sensitive-response-scanner.mjs";

const response = (changes = {}) => ({
  requestId: "owned-response", resourceType: "Fetch", responseStatusCode: 200,
  request: { method: "POST", url: "http://127.0.0.1:12345/frontend-host" },
  responseHeaders: [{ name: "Content-Type", value: "application/json" }], ...changes,
});

test("reads paused JSON before resuming unchanged response", async () => {
  const calls = [], scanned = [];
  const scanner = sensitiveResponseScanner(async (method, params) => {
    calls.push([method, params]);
    return method === "Fetch.getResponseBody" ? { body: '{"owned":true}', base64Encoded: false } : {};
  }, value => scanned.push(value), () => "/frontend-host");
  scanner.observe(response());
  await scanner.drain();
  assert.deepEqual(calls, [
    ["Fetch.getResponseBody", { requestId: "owned-response" }],
    ["Fetch.continueResponse", { requestId: "owned-response" }],
  ]);
  assert.ok(scanned.includes('{"owned":true}'));
  assert.deepEqual(scanner.failures, []);
  assert.equal(scanner.pendingCount, 0);
});

test("empty CORS204 checks headers without asking Chrome for a nonexistent body", async () => {
  const calls = [], scanned = [];
  const scanner = sensitiveResponseScanner(async method => { calls.push(method); return {}; },
    value => scanned.push(value), () => "/frontend-host");
  scanner.observe(response({ request: { method: "OPTIONS", url: "http://127.0.0.1:12345/frontend-host" },
    responseStatusCode: 204 }));
  await scanner.drain();
  assert.deepEqual(calls, ["Fetch.continueResponse"]);
  assert.equal(scanned.length, 2);
  assert.equal(scanner.counts.emptyCorsPreflights, 1);
  assert.deepEqual(scanner.failures, []);
});

test("a 200 preflight is still body-scanned", async () => {
  const calls = [];
  const scanner = sensitiveResponseScanner(async method => {
    calls.push(method);
    return { body: "{}", base64Encoded: false };
  }, () => {}, () => "/frontend-host");
  scanner.observe(response({ request: { method: "OPTIONS", url: "http://127.0.0.1:12345/frontend-host" } }));
  await scanner.drain();
  assert.deepEqual(calls, ["Fetch.getResponseBody", "Fetch.continueResponse"]);
  assert.equal(scanner.counts.bodies, 1);
});

test("failed reads remain failures and do not skip resuming the request", async () => {
  const calls = [];
  const scanner = sensitiveResponseScanner(async method => {
    calls.push(method);
    if (method === "Fetch.getResponseBody") throw Error("controlled unavailable response");
  }, () => {}, () => "/frontend-host");
  scanner.observe(response());
  await scanner.drain();
  assert.equal(scanner.failures[0].stage, "body");
  assert.deepEqual(calls, ["Fetch.getResponseBody", "Fetch.continueResponse"]);
});

test("invalid UTF8, credential findings and rejected resume cannot become a pass", async () => {
  for (const failure of ["utf8", "credential", "resume"]) {
    const scanner = sensitiveResponseScanner(async method => {
      if (method === "Fetch.continueResponse" && failure === "resume") throw Error("controlled resume failure");
      return { body: failure === "utf8" ? "/w==" : "private-marker", base64Encoded: failure === "utf8" };
    }, value => { if (value === "private-marker" && failure === "credential") throw Error("controlled credential leak"); },
    () => "/frontend-host");
    scanner.observe(response());
    await scanner.drain();
    assert.equal(scanner.failures.length, 1);
  }
});

test("redirects and nonempty204 claims fail without replaying or replacing data", async () => {
  for (const value of [response({ responseStatusCode: 302 }),
    response({ responseStatusCode: 204, request: { method: "OPTIONS" },
      responseHeaders: [{ name: "Content-Length", value: "12" }] })]) {
    const calls = [];
    const scanner = sensitiveResponseScanner(async method => { calls.push(method); }, () => {}, () => "/frontend-host");
    scanner.observe(value);
    await scanner.drain();
    assert.equal(scanner.failures.length, 1);
    assert.deepEqual(calls, ["Fetch.continueResponse"]);
  }
});

test("drain waits for every body and its resume operation", async () => {
  let release;
  const gate = new Promise(done => { release = done; });
  const scanner = sensitiveResponseScanner(async method => {
    if (method === "Fetch.getResponseBody") { await gate; return { body: "{}", base64Encoded: false }; }
  }, () => {}, () => "/frontend-host");
  scanner.observe(response());
  let finished = false;
  const draining = scanner.drain().then(() => { finished = true; });
  await new Promise(done => setImmediate(done));
  assert.equal(finished, false);
  release();
  await draining;
  assert.equal(scanner.counts.bodies, 1);
  assert.equal(scanner.pendingCount, 0);
});

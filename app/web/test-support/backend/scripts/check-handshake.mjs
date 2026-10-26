import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { resolve } from "node:path";

// Point at the product's generated Wasm package; do not install a second ws version.
const require = createRequire(resolve(process.argv[2]));
const WebSocket = require("ws");
const token = process.env.KODEX_WEB_FIXTURE_TOKEN;
assert.ok(token && token.length >= 32, "Supply the runtime-only fixture token.");
const valid = { token, instance: "web-fixture-995", protocol: 1 };

async function connect(label, { hello = valid, origin = "http://127.0.0.1:18795", host = "127.0.0.1:18796", accepted = false, binary = false } = {}) {
  await new Promise((done, fail) => {
    let admitted = false;
    const socket = new WebSocket("ws://127.0.0.1:18796/fixture", { origin, headers: { Host: host } });
    const timeout = setTimeout(() => { socket.terminate(); fail(new Error(`${label}: timed out`)); }, 10_000);
    socket.on("open", () => { if (hello !== null) socket.send(JSON.stringify(hello), { binary }); });
    socket.on("message", (data) => {
      try {
        assert.ok(accepted, `${label}: rejected handshake reached RPC admission`);
        const reply = JSON.parse(data.toString());
        assert.equal(reply.ready, true);
        assert.equal(typeof reply.cwd, "string");
        admitted = true;
        socket.close();
      } catch (error) { clearTimeout(timeout); socket.terminate(); fail(error); }
    });
    socket.on("error", (error) => { clearTimeout(timeout); fail(error); });
    socket.on("close", (code) => {
      clearTimeout(timeout);
      try {
        if (accepted) assert.equal(admitted, true);
        else assert.equal(code, 1008, `${label}: transport failure is not policy rejection`);
        done();
      } catch (error) { fail(error); }
    });
  });
  console.log(`PASS ${label}`);
}

await connect("wrong-token", { hello: { ...valid, token: "wrong" } });
await connect("wrong-instance", { hello: { ...valid, instance: "wrong" } });
await connect("wrong-protocol", { hello: { ...valid, protocol: -1 } });
await connect("wrong-origin", { origin: "http://untrusted.invalid" });
await connect("wrong-host", { host: "untrusted.invalid" });
await connect("binary-hello", { binary: true });
await connect("missing-hello", { hello: null });
await connect("accepted-runtime-hello", { accepted: true });

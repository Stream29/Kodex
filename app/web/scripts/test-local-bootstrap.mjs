import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const html = await readFile(new URL("../src/wasmJsMain/resources/index.html", import.meta.url), "utf8");
const inline = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const path = `/web/${"a".repeat(64)}/`;
const ticket = "b".repeat(64);

function page(pathname, hash, storage = new Map()) {
    const location = { pathname, hash, search: "" };
    const replacements = [];
    const window = {};
    vm.runInNewContext(inline, {
        location, window,
        sessionStorage: {
            getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, value),
            removeItem: key => storage.delete(key),
        },
        history: { replaceState: (...args) => { replacements.push(args); location.hash = ""; } },
    });
    return { location, window, replacements };
}

test("frontend capability fragment is removed before Wasm and read once per document", () => {
    assert.ok(html.indexOf("<script>") < html.indexOf('src="kodex-web.js"'));
    const context = page(path, `#${ticket}`);
    assert.equal(context.location.hash, "");
    assert.equal(context.replacements.length, 1);
    assert.equal(context.replacements[0][2], path);
    assert.equal(context.window.kodexTakeLaunchTicket(), ticket);
    assert.equal(context.window.kodexTakeLaunchTicket(), null);
});

test("reload without a capability fails closed and independent distribution is untouched", () => {
    assert.equal(page(path, "").window.kodexTakeLaunchTicket(), null);
    const standalone = page("/", "#unrelated");
    assert.equal(standalone.location.hash, "#unrelated");
    assert.equal(standalone.replacements.length, 0);
    assert.equal(standalone.window.kodexTakeLaunchTicket(), null);
});

test("saved capability is keyed by launch path; an empty store has none", () => {
    const storage = new Map();
    assert.equal(page(path, `#${ticket}`, storage).window.kodexTakeLaunchTicket(), ticket);
    assert.deepEqual([...storage], [[`kodex.frontend-capability:${path}`, ticket]]);
    assert.equal(page(path, "", storage).window.kodexTakeLaunchTicket(), ticket);
    assert.equal(page(`/web/${"c".repeat(64)}/`, "", storage).window.kodexTakeLaunchTicket(), null);
    assert.equal(page(path, "", new Map()).window.kodexTakeLaunchTicket(), null);
});

test("malformed fragment invalidates a saved capability and is removed", () => {
    const storage = new Map([[`kodex.frontend-capability:${path}`, ticket]]);
    const context = page(path, "#wrong", storage);
    assert.equal(context.location.hash, "");
    assert.equal(context.window.kodexTakeLaunchTicket(), null);
    assert.equal(storage.size, 0);
});

test("storage-denied initial launch works in memory but reload cannot silently authenticate", () => {
    const denied = { get() { throw Error("denied"); }, set() { throw Error("denied"); } };
    assert.equal(page(path, `#${ticket}`, denied).window.kodexTakeLaunchTicket(), ticket);
    assert.equal(page(path, "", denied).window.kodexTakeLaunchTicket(), null);
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, symlinkSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';

const script = fileURLToPath(new URL('./embed-frontend.mjs', import.meta.url));
function fixture(run) {
    const directory = mkdtempSync(join(tmpdir(), 'kauri-embedding-'));
    try {
        const input = join(directory, 'frontend');
        const output = join(directory, 'frontend.pack');
        mkdirSync(input);
        const pack = () => execFileSync(process.execPath, [script, 'pack', input, output], {stdio: 'pipe'});
        run({input, output, pack, directory});
    } finally { rmSync(directory, {recursive: true, force: true}); }
}

test('archive layout, deterministic ordering and source-map exclusion', () => fixture(({input, output, pack}) => {
    writeFileSync(join(input, 'index.html'), '<html>');
    writeFileSync(join(input, 'z.wasm'), Buffer.from([0, 1, 255]));
    writeFileSync(join(input, 'z.wasm.map'), 'not shipped');
    pack();
    const archive = readFileSync(output);
    assert.equal(archive.readUInt32BE(0), 0x4b415552);
    assert.equal(archive.readUInt32BE(4), 2);
    let cursor = 8;
    const entries = [];
    for (let i = 0; i < 2; i++) {
        const nameLength = archive.readUInt32BE(cursor);
        const size = archive.readUInt32BE(cursor + 4);
        cursor += 8;
        const name = archive.subarray(cursor, cursor + nameLength).toString();
        cursor += nameLength;
        entries.push([name, archive.subarray(cursor, cursor + size)]);
        cursor += size;
    }
    assert.deepEqual(entries, [['index.html', Buffer.from('<html>')], ['z.wasm', Buffer.from([0, 1, 255])]]);
    assert.equal(cursor, archive.length);
    pack();
    assert.deepEqual(readFileSync(output), archive);
    writeFileSync(join(input, 'index.html'), 'changed');
    pack();
    assert.notDeepEqual(readFileSync(output), archive);
}));
test('missing index is rejected', () => fixture(({pack}) => assert.throws(pack)));
test('symlinks are rejected', () => fixture(({input, pack, directory}) => {
    writeFileSync(join(input, 'index.html'), 'index');
    writeFileSync(join(directory, 'private'), 'not shipped');
    symlinkSync(join(directory, 'private'), join(input, 'linked'));
    assert.throws(pack);
}));
test('unsupported path is rejected', () => fixture(({input, pack}) => {
    writeFileSync(join(input, 'index.html'), 'index');
    writeFileSync(join(input, 'space name'), 'invalid');
    assert.throws(pack);
}));

import {lstatSync, readdirSync, readFileSync, mkdirSync, writeFileSync, renameSync} from 'node:fs';
import {resolve, dirname, relative} from 'node:path';
import {execFileSync} from 'node:child_process';

const [operation, input, output, target, compiler] = process.argv.slice(2);
mkdirSync(dirname(output), {recursive: true});
if (operation === 'pack') {
    const root = resolve(input);
    if (lstatSync(root).isSymbolicLink()) throw Error(`Symlink frontend root: ${root}`);
    const files = [];
    const visit = directory => {
        for (const name of readdirSync(directory).sort()) {
            const path = resolve(directory, name);
            const stat = lstatSync(path);
            if (stat.isSymbolicLink()) throw Error(`Symlink in frontend: ${path}`);
            if (stat.isDirectory()) visit(path);
            else if (stat.isFile() && !name.endsWith('.map')) files.push(path);
        }
    };
    visit(root);
    if (!files.includes(resolve(root, 'index.html'))) throw Error('Missing index.html');
    const integer = n => { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b; };
    const parts = [integer(0x4b415552), integer(files.length)];
    for (const path of files) {
        const name = relative(root, path).split('\\').join('/');
        if (!name.split('/').every(p => /^[A-Za-z0-9_@.-]+$/.test(p) && p !== '.' && p !== '..'))
            throw Error(`Unsupported asset path: ${name}`);
        const key = Buffer.from(name);
        const bytes = readFileSync(path);
        parts.push(integer(key.length), integer(bytes.length), key, bytes);
    }
    const archive = Buffer.concat(parts);
    if (archive.length > 0x7fffffff) throw Error('Embedded archive exceeds 2 GiB');
    writeFileSync(`${output}.tmp`, archive);
    renameSync(`${output}.tmp`, output);
} else if (operation === 'embed') {
    const triples = {
        macosArm64: 'arm64-apple-macos12',
        mingwX64: 'x86_64-w64-windows-gnu',
        linuxX64: 'x86_64-unknown-linux-gnu',
        linuxArm64: 'aarch64-unknown-linux-gnu',
    };
    if (!triples[target]) throw Error(`Unknown target: ${target}`);
    const apple = target === 'macosArm64';
    const prefix = apple ? '_' : '';
    const section = apple ? '.section __TEXT,__kauri,regular,no_dead_strip'
        : target === 'mingwX64' ? '.section .rdata,"dr"' : '.section .rodata.kauri,"a"';
    const source = `${output}.s`;
    writeFileSync(source, `${section}
.p2align 3
.globl ${prefix}kauri_assets_start
${prefix}kauri_assets_start:
.incbin ${JSON.stringify(resolve(input))}
.globl ${prefix}kauri_assets_end
${prefix}kauri_assets_end:
${target.startsWith('linux') ? '.section .note.GNU-stack,"",@progbits' : ''}
`);
    execFileSync(compiler, [`--target=${triples[target]}`, '-c', source, '-o', `${output}.tmp`],
        {stdio: 'inherit'});
    renameSync(`${output}.tmp`, output);
} else {
    throw Error('Expected pack or embed');
}

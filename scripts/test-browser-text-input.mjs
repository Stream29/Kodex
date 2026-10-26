import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// DOM assumptions and production focus helpers only: no compiled Kotlin/Compose or OS clipboard/IME.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = await readFile(join(root,
  'app/web/renderer/src/wasmJsMain/kotlin/io/github/stream29/kodex/app/ui/BrowserEditor.kt'), 'utf8');
assert.match(source, /document\.createElement\("textarea"\)/);
const field = await readFile(join(root,
  'app/component/widgets/src/commonMain/kotlin/io/github/stream29/kodex/app/ui/TextInput.kt'), 'utf8');
assert.match(field, /UiEditor\(/);
assert.match(field, /multiline = false/);
assert.match(field, /maximumLines = 1/);
function helper(name) {
  const match = source.match(new RegExp(`private fun ${name}\\([^\\n]*?= js\\("""([\\s\\S]*?)"""\\)`));
  assert.ok(match, `Find production ${name}; never substitute a test implementation.`);
  return match[1];
}
const findCanvas = helper('editorCanvas');
const releaseFocus = helper('restoreReleasedEditorCanvas');
const script = `
  const findCanvas = new Function('input', ${JSON.stringify(findCanvas)});
  const releaseFocus = new Function('canvas', 'input', ${JSON.stringify(releaseFocus)});
  const results = [];
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  try {
    const host = document.createElement('div');
    const canvas = document.createElement('canvas');
    canvas.tabIndex = 0;
    host.appendChild(canvas);
    const input = document.createElement('textarea');
    input.rows = 1;
    input.wrap = 'off';
    host.appendChild(input);
    document.body.appendChild(host);
    input.value = 'A\\n😀\\nB';
    input.setSelectionRange(2, 5, 'backward');
    check(input.value === 'A\\n😀\\nB', 'Single row must not strip clipboard text');
    check(input.selectionStart === 2 && input.selectionEnd === 5 &&
      input.selectionDirection === 'backward', 'Native selection uses UTF-16 offsets');
    results.push('single-row text and backward UTF-16 range');
    input.value = '<img src=x onerror=alert(1)>\\n<script>bad()</script>';
    check(input.children.length === 0 && input.value.includes('<script>'),
      'Untrusted markup stays literal');
    results.push('plain-text safety');

    input.value = 'original';
    input.focus();
    input.setSelectionRange(0, 8);
    check(document.execCommand('insertText', false, 'changed'), 'Browser native edit command');
    check(input.value === 'changed', 'Native replacement');
    check(document.execCommand('undo'), 'Browser native undo command');
    check(input.value === 'original', 'Native undo history');
    results.push('DOM edit and undo platform assumption (not OS paste)');

    check(findCanvas(input) === canvas, 'Nearest renderer canvas');
    input.remove();
    releaseFocus(canvas, input);
    check(document.activeElement === canvas, 'Released native field returns focus to its canvas');
    results.push('production release helper restores canvas');

    const other = document.createElement('textarea');
    host.appendChild(other);
    other.focus();
    releaseFocus(canvas, input);
    check(document.activeElement === other, 'Release must not steal another field');
    canvas.remove();
    other.remove();
    releaseFocus(canvas, input);
    check(document.activeElement === document.body, 'Detached canvas must not receive focus');
    results.push('production release helper respects other fields and detachment');

    const shadowHost = document.createElement('div');
    const shadow = shadowHost.attachShadow({ mode: 'open' });
    const shadowCanvas = document.createElement('canvas');
    shadow.appendChild(shadowCanvas);
    host.appendChild(shadowHost);
    host.appendChild(input);
    check(findCanvas(input) === shadowCanvas, 'Canvas discovery inside a shadow root');
    results.push('production canvas discovery in shadow DOM');
    host.remove();
    document.body.textContent = JSON.stringify({ passed: results.length, results });
    document.body.dataset.result = 'pass';
  } catch (error) {
    document.body.textContent = String(error.stack || error);
    document.body.dataset.result = 'fail';
  }
`;
const temp = await mkdtemp(join(tmpdir(), 'kodex-native-input-'));
let browser;
try {
  const page = join(temp, 'test.html');
  await writeFile(page, `<!doctype html><html><body><script>${script.replaceAll('</script', '<\\/script')}</script></body></html>`);
  const output = await new Promise((resolveOutput, reject) => {
    browser = spawn(process.env.CHROME_BIN || 'google-chrome', [
      '--headless', '--disable-gpu', '--disable-background-networking', '--disable-sync',
      '--disable-extensions', '--no-first-run', '--no-default-browser-check',
      `--user-data-dir=${join(temp, 'profile')}`, '--dump-dom', pathToFileURL(page).href,
    ], { detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      stopBrowser();
      reject(new Error('Private headless browser timed out'));
    }, 20_000);
    browser.stdout.on('data', bytes => { stdout += bytes; });
    browser.stderr.on('data', bytes => { stderr += bytes; });
    browser.on('error', error => { clearTimeout(timer); reject(error); });
    browser.on('close', code => {
      clearTimeout(timer);
      if (code !== 0) reject(new Error(`Headless browser exit ${code}: ${stderr}`));
      else resolveOutput(stdout);
    });
  });
  assert.match(output, /data-result="pass"/, output);
  console.log(output.match(/<body[^>]*>([\s\S]*?)<\/body>/)?.[1] || output);
} finally {
  stopBrowser();
  await rm(temp, { recursive: true, force: true });
}

function stopBrowser() {
  if (!browser?.pid) return;
  try {
    if (process.platform === 'win32') browser.kill('SIGKILL');
    else process.kill(-browser.pid, 'SIGKILL');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

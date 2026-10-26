import { createServer } from "node:http";
import { realpath, readFile, stat } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";

const root = await realpath(process.argv[2]);
const types = new Map([
  [".html", "text/html; charset=utf-8"], [".js", "text/javascript; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"], [".wasm", "application/wasm"],
  [".json", "application/json"], [".map", "application/json"],
  [".ttf", "font/ttf"], [".woff", "font/woff"], [".woff2", "font/woff2"],
  [".txt", "text/plain; charset=utf-8"], [".md", "text/plain; charset=utf-8"],
]);
const server = createServer(async (request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  try {
    const pathname = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname);
    const path = pathname.endsWith("/") ? `${pathname}index.html` : pathname;
    const file = await realpath(resolve(root, `.${path}`));
    const type = types.get(extname(file));
    const metadata = await stat(file);
    if (!file.startsWith(root + sep) || !type || !metadata.isFile()) {
      response.writeHead(404).end();
      return;
    }
    const bytes = request.method === "HEAD" ? undefined : await readFile(file);
    response.writeHead(200, {
      "Content-Type": type, "Cache-Control": "no-store", "Content-Length": bytes?.length ?? metadata.size,
    });
    response.end(bytes);
  } catch {
    if (response.headersSent) response.destroy();
    else response.writeHead(404).end();
  }
});
server.listen(18795, "127.0.0.1", () => console.log("STATIC_FIXTURE_READY 127.0.0.1:18795"));
process.on("SIGTERM", () => server.close());

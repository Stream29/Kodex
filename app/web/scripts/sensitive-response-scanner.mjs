import assert from "node:assert/strict";

/** Test-only CDP observer: inspect paused responses, then resume the original bytes unchanged. */
export function sensitiveResponseScanner(send, scan, safePath) {
  const pending = new Set();
  const failures = [];
  const counts = { bodies: 0, emptyCorsPreflights: 0 };

  async function inspect(value) {
    const metadata = { path: safePath(value.request?.url), type: value.resourceType, status: value.responseStatusCode };
    let stage = "headers";
    try {
      scan(value.request);
      scan(value.responseHeaders);
      assert.ok(Number.isInteger(value.responseStatusCode) && !value.responseErrorReason);
      if (value.request.method === "OPTIONS" && value.responseStatusCode === 204) {
        const length = value.responseHeaders?.find(header => header.name.toLowerCase() === "content-length")?.value;
        assert.ok(length === undefined || length === "0");
        counts.emptyCorsPreflights++;
      } else {
        stage = "body";
        assert.ok(value.responseStatusCode < 300 || value.responseStatusCode >= 400,
          "The private host does not redirect its credential-bearing responses.");
        const body = await send("Fetch.getResponseBody", { requestId: value.requestId });
        scan(body.base64Encoded ? new TextDecoder("utf-8", { fatal: true }).decode(Buffer.from(body.body, "base64")) : body.body);
        counts.bodies++;
      }
    } catch {
      failures.push({ ...metadata, stage });
    } finally {
      try {
        await send("Fetch.continueResponse", { requestId: value.requestId });
      } catch {
        failures.push({ ...metadata, stage: "resume-original-response" });
      }
    }
  }

  return {
    observe(value) {
      const task = inspect(value);
      pending.add(task);
      void task.finally(() => pending.delete(task));
    },
    async drain() {
      while (pending.size) await Promise.all([...pending]);
    },
    get pendingCount() { return pending.size; },
    failures, counts,
  };
}

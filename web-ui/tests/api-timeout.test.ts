import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import api, { ApiError } from "../app/services/api";

test("a timed-out phone request gives background guidance without becoming an auth error", async () => {
  const server = createServer((request, response) => {
    if (request.url === "/unauthorized") {
      response.writeHead(401, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "Unauthorized", code: 401 }));
    }
    // /slow deliberately never responds, matching a frozen Android process.
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const options = { prefixUrl: `http://127.0.0.1:${address.port}`, retry: 0, timeout: 100 };
  try {
    await assert.rejects(api.get("slow", options), error =>
      error instanceof ApiError && error.code === 0 && error.message.includes("允许后台耗电"));
    await assert.rejects(api.get("unauthorized", options), error =>
      error instanceof ApiError && error.code === 401 && error.message === "Unauthorized");
  } finally {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

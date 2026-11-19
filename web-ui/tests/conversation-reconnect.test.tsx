import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { chromium } from "@playwright/test";

test("production WebUI restores the conversation snapshot after an SSE disconnect", async () => {
  const root = resolve("build/client");
  let connections = 0;
  const settings = {
    assistantId: "a",
    chatModelId: "m",
    favoriteModels: [],
    assistantTags: [],
    assistants: [{ id: "a", name: "Test assistant", tags: [] }],
    providers: [
      {
        id: "p",
        name: "Test provider",
        enabled: true,
        models: [{ id: "m", modelId: "test", displayName: "Test model", type: "CHAT" }],
      },
    ],
    displaySetting: { userNickname: "Test user", sendOnEnter: true },
    mcpServers: [],
    searchServices: [],
    quickMessages: [],
    enableSuggestion: false,
    webServerJwtEnabled: false,
  };
  const conversation = (text: string) => ({
    id: "c1",
    assistantId: "a",
    title: "Reconnect test",
    messages: [
      {
        id: "node1",
        selectIndex: 0,
        messages: [
          {
            id: "message1",
            role: "ASSISTANT",
            createdAt: new Date().toISOString(),
            parts: [{ type: "text", text }],
            modelId: "m",
          },
        ],
      },
    ],
    chatSuggestions: [],
    isPinned: false,
    createAt: 1,
    updateAt: Date.now(),
    isGenerating: false,
  });
  const server = createServer(async (request, response) => {
    const path = new URL(request.url!, "http://localhost").pathname;
    if (path === "/api/events" || path === "/api/conversations/c1/stream") {
      response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
      if (path === "/api/events") {
        response.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
      } else {
        connections++;
        response.write(
          `event: snapshot\ndata: ${JSON.stringify({
            type: "snapshot",
            seq: 1,
            conversation: conversation(
              connections === 1 ? "Before disconnect" : "Recovered snapshot",
            ),
            serverTime: Date.now(),
          })}\n\n`,
        );
        if (connections === 1) setTimeout(() => response.end(), 800);
      }
      return;
    }
    if (path.startsWith("/api/")) {
      response.setHeader("Content-Type", "application/json");
      if (path === "/api/conversations/c1") {
        // A slow REST request must not replace the newer snapshot after reconnect.
        setTimeout(() => response.end(JSON.stringify(conversation("Stale REST snapshot"))), 2200);
        return;
      }
      response.end(
        JSON.stringify(
          path === "/api/conversations"
            ? { items: [conversation("Initial snapshot")], hasMore: false }
            : [],
        ),
      );
      return;
    }
    const file = resolve(root, `.${decodeURIComponent(path)}`);
    if (!file.startsWith(root + sep)) {
      response.writeHead(403).end();
      return;
    }
    try {
      const contents = await readFile(
        path.startsWith("/assets/") ? file : resolve(root, "index.html"),
      );
      const types: Record<string, string> = {
        ".js": "text/javascript",
        ".css": "text/css",
        ".woff2": "font/woff2",
      };
      response.setHeader(
        "Content-Type",
        path.startsWith("/assets/")
          ? (types[extname(file)] ?? "application/octet-stream")
          : "text/html",
      );
      response.end(contents);
    } catch {
      response.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const browser = await chromium.launch({
    channel:
      process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === "win32" ? "msedge" : undefined),
    headless: true,
  });
  try {
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${(server.address() as AddressInfo).port}/c/c1`);
    await page.getByText("Before disconnect", { exact: true }).first().waitFor();
    await page.getByText("Recovered snapshot", { exact: true }).first().waitFor();
    assert.equal(connections, 2);
    await page.waitForTimeout(600);
    assert.equal(await page.getByText("Stale REST snapshot", { exact: true }).count(), 0);
    assert.ok((await page.getByText("Recovered snapshot", { exact: true }).count()) > 0);
    assert.equal(await page.getByText("Before disconnect", { exact: true }).count(), 0);
  } finally {
    await browser.close();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

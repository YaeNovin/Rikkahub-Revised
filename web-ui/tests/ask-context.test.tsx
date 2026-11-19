import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { readFile } from "node:fs/promises";
import { resolve, extname } from "node:path";
import { chromium, expect } from "@playwright/test";

test("ask_user draft restores and a per-question timeout rejects only that answer; context compress shows progress", async () => {
  const root = resolve("build/client");
  const settings = { assistantId: "a", chatModelId: "m", favoriteModels: [], assistantTags: [], assistants: [{ id: "a", name: "Web", tags: [] }], providers: [{ id: "p", name: "Mock", enabled: true, models: [{ id: "m", modelId: "mock", displayName: "Mock", type: "CHAT" }] }], displaySetting: { sendOnEnter: true }, mcpServers: [], searchServices: [], quickMessages: [], webServerJwtEnabled: false };
  const input = JSON.stringify({ questions: [{ id: "text", question: "输入备注", selection_type: "text", required: true }, { id: "confirm", question: "危险确认", selection_type: "confirm", danger: true, timeout_seconds: 2, required: true }] });
  let draft: Record<string, unknown> = {};
  let deadline: number | undefined;
  let approval: { answer?: string; cancelled?: boolean } | undefined;
  let compressed = false;
  let compressCalls = 0;
  const metadata = () => ({ ask_user_interaction: { answers: draft, timers: deadline ? { confirm: { wall_deadline: deadline } } : {} } });
  const conversation = () => ({ id: "c1", assistantId: "a", title: "Ask test", messages: [{ id: "n", selectIndex: 0, messages: [{ id: "msg", role: "ASSISTANT", createdAt: new Date().toISOString(), parts: [{ type: "tool", toolCallId: "t", toolName: "ask_user", input, output: [], approvalState: { type: "pending" }, metadata: metadata() }], modelId: "m" }] }], chatSuggestions: [], isPinned: false, createAt: 1, updateAt: 1, isGenerating: false });
  const server = createServer(async (request, response) => {
    const path = new URL(request.url!, "http://localhost").pathname;
    if (path === "/api/events" || path === "/api/conversations/c1/stream") {
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      response.write(path === "/api/events" ? `event: settings\ndata: ${JSON.stringify(settings)}\n\n` : `event: snapshot\ndata: ${JSON.stringify({ type: "snapshot", seq: 1, conversation: conversation() })}\n\n`); return;
    }
    if (path.startsWith("/api/")) {
      response.setHeader("Content-Type", "application/json");
      if (path === "/api/conversations/paged") { response.end(JSON.stringify({ items: [], nextOffset: null })); return; }
      if (path === "/api/conversations/c1") { response.end(JSON.stringify(conversation())); return; }
      if (path.endsWith("/ask-user/draft")) {
        let raw = ""; for await (const chunk of request) raw += chunk;
        const body = JSON.parse(raw); draft = body.answers;
        if (body.displayed.includes("confirm") && !deadline) deadline = Date.now() + 2000;
        if (deadline && Date.now() >= deadline) draft.confirm = false;
        response.end(JSON.stringify({ metadata: metadata(), serverTime: Date.now() })); return;
      }
      if (path.endsWith("/tool-approval")) { let raw = ""; for await (const chunk of request) raw += chunk; approval = JSON.parse(raw); response.end('{"status":"accepted"}'); return; }
      if (path.endsWith("/context")) { response.end(JSON.stringify({ usedTokens: compressed ? 300 : 900, capacityTokens: 2000, remainingTokens: compressed ? 1700 : 1100, percentage: compressed ? 15 : 45, measuredInputTokens: 800, calibrated: true, basedOnRequest: true, breakdown: { TEXT: compressed ? 300 : 900 }, summary: compressed ? "压缩摘要" : "", processingStatus: "", compressing: false })); return; }
      if (path.endsWith("/compress")) { compressCalls++; setTimeout(() => { compressed = true; response.end('{"status":"compressed"}'); }, 600); return; }
      response.end("[]"); return;
    }
    try { const file = path.startsWith("/assets/") ? resolve(root, `.${path}`) : resolve(root, "index.html"); response.setHeader("Content-Type", path.startsWith("/assets/") ? ({ ".js": "text/javascript", ".css": "text/css" }[extname(file)] ?? "application/octet-stream") : "text/html"); response.end(await readFile(file)); }
    catch { response.writeHead(404).end(); }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === "win32" ? "msedge" : undefined), headless: true });
  try {
    const page = await browser.newPage(); await page.addInitScript(() => localStorage.setItem("lang", "zh-CN"));
    const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${(server.address() as AddressInfo).port}/c/c1`);
    await page.getByPlaceholder("输入备注", { exact: true }).fill("保留这个草稿");
    await expect.poll(() => draft.text).toBe("保留这个草稿");
    await page.reload();
    await expect(page.getByPlaceholder("输入备注", { exact: true })).toHaveValue("保留这个草稿");
    await expect.poll(() => draft.confirm).toBe(false);
    assert.equal(approval, undefined, "timeout must not cancel the entire form");
    await page.getByRole("button", { name: "提交", exact: true }).click();
    await expect.poll(() => approval?.answer).toBeTruthy();
    assert.deepEqual(JSON.parse(approval!.answer!).answers, { text: "保留这个草稿", confirm: false });
    await page.getByText(/^上下文：/).click();
    await expect(page.getByText(/最近请求输入/)).toContainText("800");
    await page.getByRole("button", { name: "手动压缩上下文" }).click();
    await expect(page.getByText("正在压缩上下文，历史消息会保留…")).toBeVisible();
    await expect.poll(() => compressed).toBe(true);
    await expect(page.getByText(/^上下文：/)).toContainText("300");
    assert.equal(compressCalls, 1);
    assert.deepEqual(errors, []);
    await page.screenshot({ path: "../docs/local/web-ask-context.png", fullPage: true });
  } finally { await browser.close(); server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())); }
});

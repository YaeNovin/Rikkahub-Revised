// Real Android service + real WebUI; the model endpoint alone is a local deterministic stub.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { chromium, expect } from "@playwright/test";
import type { Config } from "../app/components/settings/configuration-editor";
import type { Settings } from "../app/types/settings";

const serial = process.env.ANDROID_SERIAL;
const password = process.env.WEB_DEVICE_PASSWORD;
if (!serial || !password) throw new Error("ANDROID_SERIAL and WEB_DEVICE_PASSWORD required");
const adbPath = process.env.ADB_PATH ?? "adb";
const pkg = "me.rerere.rikkahub.revised.websettingsqa";
const origin = process.env.WEB_DEVICE_URL ?? "http://127.0.0.1:18080";
const evidence = process.env.WEB_EVIDENCE_DIR ?? "../docs/local/web-background-2026-10-06/flow";
const adb = (...args: string[]) => execFileSync(adbPath, ["-s", serial, ...args], { encoding: "utf8", timeout: 15000 });
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const foreground = () => {
  adb("shell", "input", "keyevent", "KEYCODE_WAKEUP");
  adb("shell", "am", "start", "-n", `${pkg}/me.rerere.rikkahub.RouteActivity`);
};
let token = "";
async function api<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const response = await fetch(`${origin}/api/${path}`, {
    method: method ?? (body === undefined ? "GET" : "POST"),
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000),
  });
  assert.ok(response.ok, `${path}: HTTP ${response.status}`);
  if (response.status === 204) return undefined as T;
  return response.json();
}
type Document = { revision: string; values: Config };
async function save(group: string, values: Config) {
  const current = await api<Document>(`settings/manage/${group}`);
  return api<Document>(`settings/manage/${group}`, { revision: current.revision, values });
}
const modelRequests: string[] = [];
const stub = createServer(async (req, res) => {
  let body = ""; for await (const chunk of req) body += chunk;
  const request = JSON.parse(body);
  modelRequests.push(request.model);
  res.writeHead(200, { "Content-Type": "text/event-stream" });
  for (const content of ["后台", "流式回复", "完成"]) {
    res.write(`data: ${JSON.stringify({ id: "qa", object: "chat.completion.chunk", created: 1,
      model: "web-background-qa", choices: [{ index: 0, delta: { content }, finish_reason: null }] })}\n\n`);
    await wait(3000);
  }
  res.write(`data: ${JSON.stringify({ id: "qa", object: "chat.completion.chunk", created: 1,
    model: "web-background-qa", choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
  res.end("data: [DONE]\n\n");
});
await new Promise<void>(resolve => stub.listen(18090, "127.0.0.1", resolve));
await mkdir(evidence, { recursive: true });
const baseline: Record<string, Document> = {};
const conversationIds: string[] = [];
const checks: string[] = [];
const failures: string[] = [];
const controller = new AbortController();
let initialAssistant = "";
let latest: Settings | undefined;
let browser: Awaited<ReturnType<typeof chromium.launch>> | undefined;
let streamPump: Promise<void> | undefined;
try {
  foreground(); await wait(2000);
  token = (await api<{ token: string }>("auth/token", { password })).token;
  const response = await fetch(`${origin}/api/events`, { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
  assert.equal(response.status, 200);
  const reader = response.body!.getReader();
  streamPump = (async () => {
    let buffer = ""; const decoder = new TextDecoder();
    try {
      while (true) {
        const chunk = await reader.read(); if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r/g, "");
        let boundary: number;
        while ((boundary = buffer.indexOf("\n\n")) >= 0) {
          const event = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
          if (/event:\s*settings\n/.test(event)) latest = JSON.parse(event.split("\n").find(l => l.startsWith("data:"))!.slice(5));
        }
      }
    } catch (error) { if (!controller.signal.aborted) throw error; }
  })();
  await expect.poll(() => latest?.assistantId).toBeTruthy();
  initialAssistant = latest!.assistantId;
  const assistant = latest!.assistants.find(a => !a.chatModelId);
  assert.ok(assistant, "QA app needs an assistant that follows the global model");
  for (const group of ["providers", "conversations", "preferences"]) baseline[group] = await api<Document>(`settings/manage/${group}`);
  const catalog = await api<Record<string, Config[]>>("settings/manage/catalog");
  const provider = structuredClone(catalog.providers.find(p => p.type === "openai")!);
  const model = structuredClone(catalog.models[0]);
  Object.assign(model, { id: randomUUID(), modelId: "web-background-qa", displayName: "Background QA" });
  Object.assign(provider, { id: randomUUID(), name: "Background local QA", enabled: true, baseUrl: "http://127.0.0.1:18090/v1", apiKey: "qa", models: [model], useResponseApi: false });
  adb("reverse", "tcp:18090", "tcp:18090");
  await save("providers", { providers: [...baseline.providers.values.providers as Config[], provider] });
  await save("conversations", { chatModelId: model.id, enableTitleGeneration: false, enableSuggestion: false, enableGenerationRetry: false });
  await api("settings/assistant", { assistantId: assistant.id });
  await save("preferences", { sendOnEnter: true });
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors: string[] = []; page.on("pageerror", e => errors.push(e.message));
  await page.goto(origin);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('form button[type="submit"]').click();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
  for (const phase of ["home", "screen-off"]) {
    adb("shell", "input", "keyevent", phase === "home" ? "KEYCODE_HOME" : "KEYCODE_SLEEP");
    await wait(12000);
    const alternate = latest!.assistants.find(a => a.id !== assistant.id)!;
    await api("settings/assistant", { assistantId: alternate.id });
    await expect.poll(() => latest?.assistantId, { timeout: 10000 }).toBe(alternate.id);
    await api("settings/assistant", { assistantId: assistant.id });
    await expect.poll(() => latest?.assistantId, { timeout: 10000 }).toBe(assistant.id);
    await save("conversations", { titleMaxLength: phase === "home" ? 37 : 38 });
    assert.equal((await api<Document>("settings/manage/conversations")).values.titleMaxLength, phase === "home" ? 37 : 38);
    checks.push(`${phase}: assistant switch, settings save and settings SSE delivered`);
    const id = randomUUID(); conversationIds.push(id);
    await page.goto(`${origin}/c/${id}`);
    const composer = page.locator("textarea").first();
    await composer.fill(`后台测试 ${phase}`);
    await composer.press("Enter");
    await expect(page.getByText("后台流式回复完成", { exact: true })).toBeVisible({ timeout: 30000 });
    await expect.poll(async () => JSON.stringify(await api(`conversations/${id}`)), { timeout: 15000 }).toContain("后台流式回复完成");
    const history = await api<{ items: { id: string }[] }>("conversations/paged?offset=0&limit=30&folderId=none");
    assert.ok(history.items.some(c => c.id === id));
    await page.reload();
    await expect(page.getByText("后台流式回复完成", { exact: true })).toBeVisible({ timeout: 15000 });
    await expect(page.getByText("加载中...", { exact: true })).toHaveCount(0, { timeout: 15000 });
    await page.screenshot({ path: `${evidence}/${phase}-chat.png`, animations: "disabled" });
    checks.push(`${phase}: browser send, streamed reply, paged history and reload passed`);
  }
  assert.deepEqual(errors, []);
  assert.equal(modelRequests.length, 2);
  assert.ok(modelRequests.every(m => m === "web-background-qa"));
} catch (error) {
  failures.push(String(error));
} finally {
  await browser?.close();
  foreground(); await wait(1000);
  for (const id of conversationIds) {
    try { await api(`conversations/${id}/stop`, {}); await api(`conversations/${id}`, undefined, "DELETE"); }
    catch (error) { failures.push(`cleanup conversation: ${error}`); }
  }
  if (initialAssistant) {
    try { await api("settings/assistant", { assistantId: initialAssistant }); }
    catch (error) { failures.push(`restore assistant: ${error}`); }
  }
  for (const group of ["conversations", "preferences", "providers"]) {
    if (baseline[group]) try { await save(group, baseline[group].values); }
    catch (error) { failures.push(`restore ${group}: ${error}`); }
  }
  controller.abort(); await streamPump?.catch(e => failures.push(String(e)));
  adb("reverse", "--remove", "tcp:18090");
  stub.closeAllConnections(); await new Promise<void>(resolve => stub.close(() => resolve()));
  await writeFile(`${evidence}/results.json`, JSON.stringify({ checks, modelRequests, failures }, null, 2));
}
console.log({ checks, modelRequests, failures });
assert.deepEqual(failures, []);

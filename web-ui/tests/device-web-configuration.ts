// Explicit acceptance test against an isolated Android QA package, never a user's app.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { chromium, expect } from "@playwright/test";
import type { Config } from "../app/components/settings/configuration-editor";

const password = process.env.WEB_DEVICE_PASSWORD;
if (!password) throw new Error("WEB_DEVICE_PASSWORD is required for the dedicated QA app");
const initialOrigin = process.env.WEB_DEVICE_URL ?? "http://127.0.0.1:18080";
const secondOrigin = process.env.WEB_DEVICE_SECOND_URL ?? "http://127.0.0.1:18081";
const evidence = process.env.WEB_EVIDENCE_DIR ?? "../docs/local/webui-settings-2026-10-06/device";
await mkdir(evidence, { recursive: true });
let origin = initialOrigin;
let token = "";
type Document = { revision: string; values: Config };
const results: string[] = [];
const request = (path: string, body?: unknown, bearer = true) => fetch(`${origin}/api/${path}`, {
  method: body === undefined ? "GET" : "POST",
  headers: { "Content-Type": "application/json", ...(bearer ? { Authorization: `Bearer ${token}` } : {}) },
  body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000),
});
async function login() {
  const response = await request("auth/token", { password }, false);
  assert.equal(response.status, 200, "QA login");
  token = (await response.json()).token;
}
async function get<T>(path: string): Promise<T> { const response = await request(path); assert.equal(response.status, 200, `GET ${path}`); return response.json(); }
async function save(group: string, values: Config): Promise<Document> {
  const current = await get<Document>(`settings/manage/${group}`);
  const response = await request(`settings/manage/${group}`, { revision: current.revision, values });
  assert.equal(response.status, 200, `save ${group}: ${response.status === 200 ? "" : await response.text()}`);
  return response.json();
}
async function reconnect(nextOrigin: string) {
  origin = nextOrigin;
  for (let i = 0; i < 40; i++) {
    try { await login(); return; } catch { await new Promise(r => setTimeout(r, 250)); }
  }
  throw new Error("QA server did not restart");
}
await login();
const baseline: Record<string, Document> = {};
for (const group of ["preferences", "conversations", "providers", "search", "mcp", "security"]) baseline[group] = await get<Document>(`settings/manage/${group}`);
const catalog = await get<Record<string, Config[]>>("settings/manage/catalog");
const skillName = `webqa-${Date.now()}`;
let skillCreated = false;
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? "msedge", headless: true });
try {
  assert.equal((await request("settings/manage/providers", undefined, false)).status, 401);
  const cookieOnly = await fetch(`${origin}/api/settings/manage/providers`, { headers: { Cookie: `rikkahub_access=${token}` }, signal: AbortSignal.timeout(10000) });
  assert.equal(cookieOnly.status, 403);
  results.push("credential management rejects anonymous and cookie-only requests");
  const pref = await save("preferences", { sendOnEnter: !baseline.preferences.values.sendOnEnter });
  assert.equal(pref.values.sendOnEnter, !baseline.preferences.values.sendOnEnter);
  const stale = await request("settings/manage/preferences", { revision: baseline.preferences.revision, values: baseline.preferences.values });
  assert.equal(stale.status, 409);
  results.push("shared shortcut saved; stale configuration rejected with 409");
  const provider = structuredClone(catalog.providers.find(p => p.type === "openai")!);
  provider.id = randomUUID(); provider.name = "Web QA provider"; provider.enabled = false; provider.apiKey = "qa-provider-secret";
  const model = structuredClone(catalog.models[0]); model.id = randomUUID(); model.modelId = "qa-chat"; model.displayName = "QA chat";
  provider.models = [model];
  const providers = await save("providers", { providers: [...baseline.providers.values.providers as Config[], provider] });
  assert.ok(!JSON.stringify(providers).includes("qa-provider-secret"));
  const publicProvider = (providers.values.providers as Config[]).find(p => p.id === provider.id)!;
  assert.equal(publicProvider.apiKey, null);
  await save("providers", { providers: (providers.values.providers as Config[]).map(p => p.id === provider.id ? { ...p, name: "Web QA provider edited" } : p) });
  results.push("provider and chat model CRUD with write-only credentials");
  const search = structuredClone(catalog.searchServices.find(s => s.type === "tavily")!);
  search.id = randomUUID(); search.apiKey = "qa-search-secret";
  const searchResult = await save("search", { ...baseline.search.values, searchServices: [...baseline.search.values.searchServices as Config[], search] });
  assert.ok(!JSON.stringify(searchResult).includes("qa-search-secret"));
  const mcp = structuredClone(catalog.mcpServers[0]); mcp.id = randomUUID(); mcp.url = "http://127.0.0.1:9/mcp";
  mcp.commonOptions = { ...mcp.commonOptions as Config, name: "Web QA MCP", enable: false };
  await save("mcp", { mcpServers: [...baseline.mcp.values.mcpServers as Config[], mcp] });
  results.push("search and disabled MCP configuration saved to native settings");
  const empty = await get<{ revision: string }>("settings/manage/skills-new");
  const content = `---\nname: ${skillName}\ndescription: QA-only skill\n---\n\nQA body.\n`;
  let response = await request("settings/manage/skills/save", { name: skillName, content, revision: empty.revision });
  assert.equal(response.status, 200); skillCreated = true;
  const skill = await get<{ content: string; revision: string }>(`settings/manage/skills/${skillName}`);
  assert.equal(skill.content, content);
  response = await request("settings/manage/skills/save", { name: skillName, content: content + "Edited.\n", revision: skill.revision });
  assert.equal(response.status, 200);
  results.push("native Skill create, read and edit");

  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors: string[] = []; page.on("pageerror", e => errors.push(e.message));
  const network: string[] = [];
  page.on("response", r => { if (r.url().includes("/api/")) network.push(`${r.status()} ${r.url()}`); });
  page.on("requestfailed", r => network.push(`${r.failure()?.errorText} ${r.url()}`));
  await page.goto(`${origin}/settings/conversations`);
  await page.locator('input[type="password"]').waitFor({ timeout: 15000 }).catch(async error => {
    console.log({ url: page.url(), errors, network, body: (await page.locator("body").innerText()).slice(0, 2000) });
    throw error;
  });
  await page.locator('input[type="password"]').fill(password);
  await page.locator("form button[type=submit]").click();
  await page.getByLabel("标题最大长度", { exact: true }).fill("37");
  const form = page.locator("section").filter({ has: page.getByRole("heading", { name: "默认模型、提示词与会话生成", exact: true }) });
  await form.getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(form.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
  assert.equal((await get<Document>("settings/manage/conversations")).values.titleMaxLength, 37);
  await page.screenshot({ path: `${evidence}/conversations-desktop.png`, animations: "disabled" });
  await page.getByRole("link", { name: "模型与服务", exact: true }).click();
  await expect(page.getByText("Web QA provider edited", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Skills", exact: true }).click();
  await page.getByRole("button", { name: skillName, exact: true }).click();
  await expect(page.getByRole("textbox", { name: "SKILL.md 内容", exact: true })).toHaveValue(content + "Edited.\n");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: `${evidence}/skills-mobile.png`, animations: "disabled" });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  assert.deepEqual(errors, []);
  await context.close();
  results.push("real APK WebUI login, title save and Skills views in desktop and mobile viewport");

  await save("security", { webServerPort: 8081 });
  assert.equal((await request("settings/manage/security/restart", {})).status, 200);
  // Wait until the old listener is gone, then use the second USB forward.
  await new Promise(r => setTimeout(r, 1200));
  await reconnect(secondOrigin);
  const runtime = await get<{ port: number; running: boolean }>("settings/manage/runtime");
  assert.equal(runtime.port, 8081); assert.equal(runtime.running, true);
  assert.equal((await get<Document>("settings/manage/conversations")).values.titleMaxLength, 37);
  results.push("port changed to 8081; server restarted; new login and persisted configuration verified");
} finally {
  await browser.close();
  const failures: string[] = [];
  for (const group of ["preferences", "conversations", "search", "mcp", "providers"]) {
    try { await save(group, baseline[group].values); } catch (e) { failures.push(`restore ${group}: ${String(e)}`); }
  }
  if (skillCreated) {
    try { const current = await get<{ revision: string }>(`settings/manage/skills/${skillName}`); assert.equal((await request("settings/manage/skills/delete", { name: skillName, revision: current.revision, content: "" })).status, 200); } catch (e) { failures.push(`delete test skill: ${String(e)}`); }
  }
  try {
    await save("security", baseline.security.values);
    if (origin !== initialOrigin) {
      assert.equal((await request("settings/manage/security/restart", {})).status, 200);
      await new Promise(r => setTimeout(r, 1200)); await reconnect(initialOrigin);
    }
  } catch (e) { failures.push(`restore access: ${String(e)}`); }
  results.push(failures.length ? "CLEANUP INCOMPLETE" : "all QA settings restored and temporary Skill deleted");
  await writeFile(`${evidence}/results.json`, JSON.stringify({ results, failures }, null, 2));
  assert.deepEqual(failures, []);
}
console.log(results.join("\n"));

// Explicit real-device smoke test. Run only against an isolated QA/debug app.
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { chromium } from "@playwright/test";

const origin = process.env.WEB_DEVICE_URL ?? "http://127.0.0.1:18080";
const password = process.env.WEB_DEVICE_PASSWORD;
if (!password) throw new Error("WEB_DEVICE_PASSWORD is required for the isolated test app");
const evidence = process.env.WEB_EVIDENCE_DIR ?? "../docs/local/webui-device-2026-10-06";
await mkdir(evidence, { recursive: true });
const results: string[] = [];
const api = (path: string, init?: RequestInit) => fetch(`${origin}/api/${path}`, init);
assert.equal((await api("conversations")).status, 401);
results.push("anonymous API: 401");
const login = await api("auth/token", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password }) });
assert.equal(login.status, 200);
const { token } = await login.json();
const headers = { Authorization: `Bearer ${token}` };
assert.equal((await api("conversations", { headers })).status, 200);
const stream = await api("events", { headers });
const reader = stream.body!.getReader();
let initial = "";
while (!initial.includes("event: settings") || !initial.includes("data:")) {
  const chunk = await reader.read();
  if (chunk.done) throw new Error("Settings stream closed before snapshot");
  initial += new TextDecoder().decode(chunk.value);
}
assert.ok(initial.includes("settings"));
assert.ok(!initial.includes("webServerAccessPassword") && !initial.includes("apiKey") && !initial.includes(password));
const closed = (async () => { try { while (!(await reader.read()).done) {} return true; } catch { return true; } })();
const form = new FormData();
form.append("file", new Blob(["device attachment sentinel"], { type: "text/plain" }), "web-session-test.txt");
const uploaded = await api("files/upload", { method: "POST", headers, body: form });
assert.equal(uploaded.status, 201);
const id = (await uploaded.json()).files[0].id;
assert.equal((await api(`files/id/${id}`, { headers: { Cookie: `rikkahub_access=${token}` } })).status, 200);
assert.equal((await api(`files/${id}`, { method: "DELETE", headers })).status, 200);
results.push("real attachment upload + cookie download + deletion: pass");
assert.equal((await api("auth/logout", { method: "POST", headers })).status, 200);
assert.equal((await api("conversations", { headers })).status, 401);
assert.equal((await api("conversations", { headers: { Cookie: `rikkahub_access=${token}` } })).status, 401);
assert.equal(await Promise.race([closed, new Promise((r) => setTimeout(() => r(false), 4000))]), true);
results.push("logout revokes old Bearer and cookie; existing SSE closes: pass");
const browser = await chromium.launch({ channel: "msedge", headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.stack ?? error.message));
  await page.goto(origin);
  await page.locator('input[type="password"]').waitFor({ timeout: 10000 }).catch(async (error) => { console.log(errors); console.log((await page.locator("body").innerText()).slice(0, 1500)); throw error; });
  await page.locator('input[type="password"]').fill(password);
  await page.locator("form button[type=submit]").click();
  const logout = page.getByRole("button", { name: "Clear web session" });
  await logout.waitFor();
  await page.screenshot({ path: `${evidence}/desktop-authenticated.png` });
  await page.reload();
  await logout.waitFor();
  results.push("served production SPA: login and authenticated reload pass");
  await logout.click();
  await page.locator('input[type="password"]').waitFor();
  assert.ok(!(await context.cookies()).some((cookie) => cookie.name === "rikkahub_access"));
  assert.equal(await page.evaluate(() => sessionStorage.getItem("rikkahub:web-auth")), null);
  await page.screenshot({ path: `${evidence}/desktop-logged-out.png` });
  assert.deepEqual(errors, []);
  results.push("real browser logout clears cookie/storage and returns to login; no page errors: pass");
} finally { await browser.close(); }
await writeFile(`${evidence}/session-results.json`, JSON.stringify({ origin, results }, null, 2));
console.log(results.join("\n"));

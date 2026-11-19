// Explicit test against the dedicated QA package, not the user's installed app.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";

const serial = process.env.ANDROID_SERIAL;
const password = process.env.WEB_DEVICE_PASSWORD;
if (!serial || !password) throw new Error("ANDROID_SERIAL and WEB_DEVICE_PASSWORD required");
const adbPath = process.env.ADB_PATH ?? "adb";
const packageName = "me.rerere.rikkahub.revised.websettingsqa";
const origin = process.env.WEB_DEVICE_URL ?? "http://127.0.0.1:18080";
const evidence = process.env.WEB_EVIDENCE_DIR ?? "../docs/local/web-background-2026-10-06";
const duration = Number(process.env.WEB_BACKGROUND_SECONDS ?? "20");
const idleSeconds = Number(process.env.WEB_BACKGROUND_IDLE_SECONDS ?? "0");
const adb = (...args: string[]) => execFileSync(adbPath, ["-s", serial, ...args], { encoding: "utf8", timeout: 15000 });
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const foreground = () => {
  adb("shell", "input", "keyevent", "KEYCODE_WAKEUP");
  adb("shell", "am", "start", "-n", `${packageName}/me.rerere.rikkahub.RouteActivity`);
};
let token = "";
const request = (path: string, body?: unknown) => fetch(`${origin}${path}`, {
  method: body === undefined ? "GET" : "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
  body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5000),
});
const results: { phase: string; path: string; status?: number; ms: number; error?: string }[] = [];
async function probe(phase: string) {
  for (const path of ["/", "/api/conversations/paged?offset=0&limit=30&folderId=none", "/api/settings/manage/preferences"]) {
    const start = Date.now();
    try {
      const response = await request(path);
      await response.arrayBuffer();
      results.push({ phase, path, status: response.status, ms: Date.now() - start });
    } catch (e) { results.push({ phase, path, error: String(e), ms: Date.now() - start }); }
  }
}
await mkdir(evidence, { recursive: true });
try {
  foreground(); await wait(2000);
  const login = await request("/api/auth/token", { password });
  assert.equal(login.status, 200);
  token = (await login.json()).token;
  await probe("foreground");
  for (const phase of ["home", "screen-off"]) {
    if (phase === "home") adb("shell", "input", "keyevent", "KEYCODE_HOME");
    else adb("shell", "input", "keyevent", "KEYCODE_SLEEP");
    if (idleSeconds > 0) {
      console.log(`${phase}: leave the service idle for ${idleSeconds}s`);
      await wait(idleSeconds * 1000);
      await probe(`${phase}-after-idle`);
    }
    const end = Date.now() + duration * 1000;
    while (Date.now() < end) { await wait(3000); await probe(phase); }
    const pid = adb("shell", "pidof", packageName).trim();
    await writeFile(`${evidence}/${phase}-threads.txt`, adb("shell", "run-as", packageName, "ps", "-T", "-p", pid, "-o", "TID,COMM,STAT,WCHAN"));
    await writeFile(`${evidence}/${phase}-services.txt`, adb("shell", "dumpsys", "activity", "services", packageName));
  }
} finally {
  foreground(); await wait(1000);
  await writeFile(`${evidence}/background-results.json`, JSON.stringify(results, null, 2));
}
console.log(JSON.stringify(results, null, 2));
assert.ok(results.length >= 9 && results.every(r => r.status === 200), "all foreground/background requests must succeed");

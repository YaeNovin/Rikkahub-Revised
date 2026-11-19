import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { writeFile } from "node:fs/promises";

const serial = process.env.ANDROID_SERIAL;
const password = process.env.WEB_DEVICE_PASSWORD;
if (!serial || !password) throw new Error("ANDROID_SERIAL and WEB_DEVICE_PASSWORD required");
const origin = process.env.WEB_DEVICE_URL ?? "http://127.0.0.1:18080";
const adb = (...args: string[]) => execFileSync("adb", ["-s", serial, ...args], { encoding: "utf8" });
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const login = async (value: string) => {
  const response = await fetch(`${origin}/api/auth/token`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: value }) });
  assert.equal(response.status, 200);
  return (await response.json()).token as string;
};
const token = await login(password);
const headers = { Authorization: `Bearer ${token}` };
const stream = await fetch(`${origin}/api/events`, { headers });
const reader = stream.body!.getReader();
await reader.read();
const closed = (async () => { try { while (!(await reader.read()).done) {} return true; } catch { return true; } })();
const xml = adb("exec-out", "uiautomator", "dump", "/dev/tty");
const node = xml.match(/<node[^>]*class="android.widget.EditText"[^>]*password="true"[^>]*>/)?.[0];
if (!node) throw new Error("Open Debug app Web server settings with password field visible");
const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)!.slice(1).map(Number);
const [x1, y1, x2, y2] = bounds;
adb("shell", "input", "tap", String(Math.floor((x1 + x2) / 2)), String(Math.floor((y1 + y2) / 2)));
adb("shell", "input", "keyevent", "KEYCODE_MOVE_END");
try {
  adb("shell", "input", "text", "X");
  await wait(500);
  assert.equal((await fetch(`${origin}/api/conversations`, { headers })).status, 401);
  assert.equal(await Promise.race([closed, wait(3500).then(() => false)]), true);
  const updated = await login(password + "X");
  assert.equal((await fetch(`${origin}/api/conversations`, { headers: { Authorization: `Bearer ${updated}` } })).status, 200);
  console.log("password rotation rejects old JWT, closes active SSE, accepts new password: pass");
} finally {
  adb("shell", "input", "keyevent", "KEYCODE_DEL");
  adb("shell", "input", "keyevent", "4");
}
await wait(500);
const restored = await login(password);
assert.equal((await fetch(`${origin}/api/conversations`, { headers: { Authorization: `Bearer ${restored}` } })).status, 200);
await fetch(`${origin}/api/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${restored}` } });
await writeFile("../docs/local/webui-device-2026-10-06/password-rotation.txt", "PASS: old token rejected; open SSE closed; new password accepted; test password restored\n");

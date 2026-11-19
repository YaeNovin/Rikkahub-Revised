import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { chromium, expect } from "@playwright/test";

test("browser generates directly, retains failed imports across reload, and retries without regeneration", async () => {
  const root = resolve("build/client");
  let providerCalls = 0;
  let imports = 0;
  let permitImport = false;
  const importIds: string[] = [];
  const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ3sAAAAASUVORK5CYII=";
  const settings = { assistantId: "a", chatModelId: "m", assistants: [{ id: "a", name: "Web assistant" }], providers: [], favoriteModels: [], assistantTags: [], displaySetting: { sendOnEnter: true }, mcpServers: [], searchServices: [], quickMessages: [], webServerJwtEnabled: false };
  const server = createServer(async (request, response) => {
    const path = new URL(request.url!, "http://localhost").pathname;
    if (path === "/api/events") { response.writeHead(200, { "Content-Type": "text/event-stream" }); response.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`); return; }
    if (path.startsWith("/api/")) {
      response.setHeader("Content-Type", "application/json");
      if (path === "/api/images/models") { response.end(JSON.stringify([{ id: "m", modelId: "mock-image", name: "Mock image", provider: "Mock", constraints: { supportsGeneration: true, supportsEdit: true, supportsOutputCount: true, maxOutputImages: 1, maxReferenceImages: 2, supportsSize: true, supportedSizes: ["1024x1024"], supportsCustomSize: false, sizeRequestField: "size", usesJsonImageEdit: false, usesGenerationEndpointForEdit: false, blockedImageOptionKeys: [] } }])); return; }
      if (path === "/api/images/models/m/connection") { response.end(JSON.stringify({ type: "openai", baseUrl: "https://mock-image-provider.invalid/v1", apiKey: "transient-test-key", modelId: "mock-image", customHeaders: [], customBodies: [] })); return; }
      if (path === "/api/images/import") {
        let body = ""; for await (const chunk of request) body += chunk.toString();
        assert.ok(body.includes('"parameters":{"prompt":"browser-only test image","count":1,"size":"auto"}'));
        assert.ok(body.includes('"resultText":"revised browser prompt"'));
        imports++; importIds.push(/"requestId":"([^"]+)"/.exec(body)?.[1] ?? "");
        if (!permitImport) { response.writeHead(503); response.end(JSON.stringify({ error: "Simulated phone disconnect", code: 503 })); return; }
        response.end(JSON.stringify({ id: 1 })); return;
      }
      response.end("[]"); return;
    }
    const file = resolve(root, `.${decodeURIComponent(path)}`);
    if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return; }
    try { const content = await readFile(path.startsWith("/assets/") ? file : resolve(root, "index.html")); response.setHeader("Content-Type", path.startsWith("/assets/") ? ({ ".js": "text/javascript", ".css": "text/css" }[extname(file)] ?? "application/octet-stream") : "text/html"); response.end(content); }
    catch { response.writeHead(404).end(); }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === "win32" ? "msedge" : undefined), headless: true });
  try {
    const page = await browser.newPage();
    const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    await page.route("https://mock-image-provider.invalid/**", async route => {
      providerCalls++;
      assert.equal(route.request().method(), "POST");
      assert.equal(route.request().headers().authorization, "Bearer transient-test-key");
      if (route.request().url().endsWith("/edits")) {
        assert.ok(route.request().headers()["content-type"].includes("multipart/form-data"));
        assert.ok(route.request().postDataBuffer()!.includes(Buffer.from(png, "base64")));
      } else assert.equal(route.request().postDataJSON().prompt, "browser-only test image");
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [{ b64_json: png, revised_prompt: "revised browser prompt" }] }) });
    });
    const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    await page.goto(`${origin}/images`);
    await page.getByRole("button", { name: "选择图片模型" }).click();
    await expect(page.getByLabel("图片模型", { exact: true })).toHaveValue("m");
    await page.keyboard.press("Escape");
    await page.getByLabel("图片提示词").fill("browser-only test image");
    await page.getByRole("button", { name: "生成图片", exact: true }).click();
    await expect(page.getByRole("button", { name: "重试落库" })).toBeVisible();
    await expect.poll(() => imports).toBe(1);
    await page.reload();
    await expect(page.getByRole("button", { name: "重试落库" })).toBeVisible();
    assert.equal(providerCalls, 1);
    permitImport = true;
    await page.getByRole("button", { name: "重试落库" }).click();
    await expect(page.getByRole("button", { name: "重试落库" })).toHaveCount(0);
    assert.equal(providerCalls, 1);
    assert.equal(imports, 2);
    assert.ok(importIds[0]); assert.equal(importIds[0], importIds[1]);
    const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    assert.equal(storage.includes("transient-test-key"), false);
    await page.setViewportSize({ width: 390, height: 844 });
    const composer = await page.getByRole("region", { name: "图片输入框" }).boundingBox();
    assert.ok(composer && composer.y > 500 && composer.y + composer.height <= 845);
    await page.getByRole("button", { name: "图片参数", exact: true }).click();
    await expect(page.getByLabel("图片数量")).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "导入参考图" }).click();
    await expect(page.getByLabel("参考图片", { exact: true })).toBeVisible();
    await page.getByLabel("参考图片", { exact: true }).setInputFiles({ name: "reference.png", mimeType: "image/png", buffer: Buffer.from(png, "base64") });
    await page.keyboard.press("Escape");
    await expect(page.getByLabel("参考图片", { exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "编辑图片", exact: true }).click();
    await expect.poll(() => imports).toBe(3);
    assert.equal(providerCalls, 2);
    await page.screenshot({ path: "../docs/local/web-images-mobile.png", fullPage: true });
    assert.deepEqual(errors, []);
  } finally { await browser.close(); server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())); }
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { chromium, expect } from "@playwright/test";

test("settings navigation, assistant dialog, preferences and authenticated session lifecycle in production UI", async () => {
  const root = resolve("build/client");
  let token = "";
  let generation = 0;
  const streams = new Set<ServerResponse>();
  let modelUpdate: unknown;
  let version = 1;
  let messageCount = 0;
  const accessActions: string[] = [];
  const settings = {
    assistantId: "a",
    chatModelId: "m",
    favoriteModels: [],
    assistantTags: [],
    assistants: [{ id: "a", name: "Test assistant", tags: [], chatModelId: "m" }],
    providers: [
      {
        id: "p",
        name: "Test provider",
        enabled: true,
        models: [
          { id: "m", modelId: "first", displayName: "First model", type: "CHAT" },
          { id: "m2", modelId: "second", displayName: "Second model", type: "CHAT" },
        ],
      },
    ],
    displaySetting: { userNickname: "User", sendOnEnter: true },
    mcpServers: [],
    searchServices: [],
    quickMessages: [],
    webServerJwtEnabled: true,
  };
  const providerTemplate = {
    id: "new",
    type: "openai",
    name: "OpenAI",
    enabled: true,
    baseUrl: "https://example.com/v1",
    apiKey: null,
    models: [],
  };
  const modelTemplate = {
    id: "new-model",
    modelId: "",
    displayName: "",
    type: "CHAT",
    inputModalities: ["TEXT"],
    outputModalities: ["TEXT"],
    abilities: [],
    tools: [],
    customBodies: null,
    customHeaders: null,
    providerOverwrite: null,
    contextWindowTokens: null,
  };
  const searchTemplate = { id: "s", type: "tavily", apiKey: null, depth: "advanced" };
  const mcpTemplate = {
    id: "mcp",
    type: "sse",
    url: "",
    commonOptions: { enable: true, name: "", headers: null, tools: [], oauth: null },
  };
  const managed: Record<string, { revision: string; values: Record<string, unknown> }> = {
    providers: { revision: "1", values: { providers: settings.providers } },
    preferences: { revision: "1", values: { sendOnEnter: true } },
    conversations: {
      revision: "1",
      values: {
        chatModelId: "m",
        titleModelId: null,
        titleMaxLength: 24,
        titlePrompt: "Title prompt",
        enableGenerationRetry: true,
        generationRetryMaxRetries: 3,
      },
    },
    assistant: {
      revision: "1",
      values: {
        id: "a",
        name: "Test assistant",
        enableRollingContextCompression: true,
        rollingContextCompressionThresholdTokens: 8000,
        enabledSkills: [],
      },
    },
    security: {
      revision: "1",
      values: {
        webServerPort: 8080,
        webServerLocalhostOnly: true,
        webServerJwtEnabled: true,
        webServerAccessPassword: null,
      },
    },
    search: {
      revision: "1",
      values: {
        searchServices: [{ id: "b", type: "bing_local" }],
        searchCommonOptions: { resultSize: 10 },
        searchServiceSelected: 0,
      },
    },
    mcp: { revision: "1", values: { mcpServers: [] } },
  };
  const skills: Record<string, { name: string; content: string; revision: string }> = {};
  const publish = () => {
    for (const stream of streams)
      stream.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
  };
  const server = createServer(async (req, res) => {
    const path = new URL(req.url!, "http://localhost").pathname;
    const json = (status: number, body: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (path === "/api/auth/token") {
      let body = "";
      for await (const chunk of req) body += chunk;
      if (JSON.parse(body).password !== "test-password") {
        json(401, { error: "Invalid password", code: 401 });
        return;
      }
      token = `test-session-${++generation}`;
      res.setHeader("Set-Cookie", `rikkahub_access=${token}; HttpOnly; SameSite=Strict; Path=/api`);
      json(200, { token, expiresAt: Date.now() + 60_000 });
      return;
    }
    if (path === "/api/auth/logout") {
      token = "";
      res.setHeader(
        "Set-Cookie",
        "rikkahub_access=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/api",
      );
      json(200, { status: "logged_out" });
      return;
    }
    if (path.startsWith("/api/")) {
      // Match Ktor content negotiation for an SSE authentication challenge.
      if (path === "/api/events" && !req.headers.accept?.includes("application/json")) {
        res.writeHead(406).end();
        return;
      }
      const authorized =
        token &&
        (req.headers.authorization === `Bearer ${token}` ||
          req.headers.cookie?.includes(`rikkahub_access=${token}`));
      if (!authorized) {
        json(401, { error: "Unauthorized", code: 401 });
        return;
      }
      if (path === "/api/events") {
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        res.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
        streams.add(res);
        res.on("close", () => streams.delete(res));
        return;
      }
      if (path.startsWith("/api/settings/manage/")) {
        const group = path.slice("/api/settings/manage/".length);
        if (group === "providers/discover") {
          let body = ""; for await (const chunk of req) body += chunk;
          assert.equal(JSON.parse(body).apiKey, "test-only-key");
          json(200, [{ ...modelTemplate, modelId: "discovered-image" }]); return;
        }
        if (group === "catalog") {
          json(200, {
            providers: [providerTemplate],
            models: [modelTemplate],
            searchServices: [searchTemplate, { id: "js", type: "custom_js", name: "", searchScript: null, scrapeScript: null }],
            mcpServers: [mcpTemplate],
          });
          return;
        }
        if (group === "runtime") {
          json(200, {
            running: true,
            port: 8080,
            localhostOnly: true,
            localNetworkPermission: true,
          });
          return;
        }
        if (group === "mcp/status") {
          json(200, {});
          return;
        }
        if (["security/restart", "security/stop", "mcp/sync"].includes(group)) {
          accessActions.push(group);
          json(200, { status: "ok" });
          return;
        }
        if (group === "skills") {
          json(
            200,
            Object.values(skills).map((s) => ({ name: s.name, description: "Test skill" })),
          );
          return;
        }
        if (group === "skills-new") {
          json(200, { revision: "new" });
          return;
        }
        if (group.startsWith("skills/") && req.method === "GET") {
          json(200, skills[group.slice(7)]);
          return;
        }
        if (group === "skills/save" || group === "skills/delete") {
          let body = "";
          for await (const chunk of req) body += chunk;
          const skill = JSON.parse(body);
          if (group === "skills/delete") delete skills[skill.name];
          else skills[skill.name] = { ...skill, revision: String(++version) };
          json(200, { revision: String(version) });
          return;
        }
        if (!managed[group]) {
          json(404, { error: "Unsupported endpoint", code: 404 });
          return;
        }
        if (req.method === "POST") {
          let body = "";
          for await (const chunk of req) body += chunk;
          const update = JSON.parse(body);
          if (update.revision !== managed[group].revision) {
            json(409, { error: "配置已在手机修改", code: 409 });
            return;
          }
          managed[group] = {
            revision: String(++version),
            values: { ...managed[group].values, ...update.values },
          };
          if (group === "preferences")
            settings.displaySetting.sendOnEnter = update.values.sendOnEnter;
          if (group === "providers") {
            managed[group].values.providers = (
              update.values.providers as Record<string, unknown>[]
            ).map((p) => ({ ...p, apiKey: null }));
          }
          publish();
        }
        json(200, managed[group]);
        return;
      }
      if (/^\/api\/conversations\/[^/]+\/messages$/.test(path)) {
        messageCount++;
        json(200, { status: "ok" });
        return;
      }
      if (/^\/api\/conversations\/[^/]+(\/stream)?$/.test(path)) {
        const conversation = {
          id: path.split("/")[3],
          assistantId: "a",
          title: "Keyboard test",
          messages: [],
          chatSuggestions: [],
          isPinned: false,
          createAt: 1,
          updateAt: Date.now(),
          isGenerating: false,
        };
        if (path.endsWith("/stream")) {
          res.writeHead(200, { "Content-Type": "text/event-stream" });
          res.write(
            `event: snapshot\ndata: ${JSON.stringify({ type: "snapshot", seq: 1, conversation, serverTime: Date.now() })}\n\n`,
          );
        } else json(200, conversation);
        return;
      }
      if (path === "/api/settings/assistant/model") {
        let body = "";
        for await (const chunk of req) body += chunk;
        modelUpdate = JSON.parse(body);
        settings.assistants[0].chatModelId = "m2";
        for (const stream of streams)
          stream.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
        json(200, { status: "ok" });
        return;
      }
      json(200, path === "/api/conversations" ? { items: [], hasMore: false } : []);
      return;
    }
    const file = resolve(root, `.${decodeURIComponent(path)}`);
    if (!file.startsWith(root + sep) && path !== "/") {
      res.writeHead(403).end();
      return;
    }
    try {
      const types: Record<string, string> = { ".js": "text/javascript", ".css": "text/css" };
      res.setHeader(
        "Content-Type",
        path.startsWith("/assets/")
          ? (types[extname(file)] ?? "application/octet-stream")
          : "text/html",
      );
      res.end(await readFile(path.startsWith("/assets/") ? file : resolve(root, "index.html")));
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const browser = await chromium.launch({
    channel:
      process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === "win32" ? "msedge" : undefined),
    headless: true,
  });
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.goto(`http://127.0.0.1:${(server.address() as AddressInfo).port}/`);
    const password = page.locator('input[type="password"]');
    await password.fill("wrong-password");
    await page.locator("form button[type=submit]").click();
    await page.getByText("Invalid password", { exact: true }).waitFor();
    await password.fill("test-password");
    await page.locator("form button[type=submit]").click();
    const logout = page.getByRole("button", { name: "Clear web session" });
    await logout.waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/media-probe")).status), 200);
    assert.ok(
      (await context.cookies()).some(
        (cookie) => cookie.name === "rikkahub_access" && cookie.httpOnly,
      ),
    );
    await page.getByRole("button", { name: "助手配置", exact: true }).click();
    const assistantDialog = page.getByRole("dialog", { name: "Test assistant", exact: true });
    await expect(assistantDialog).toBeVisible();
    await assistantDialog.getByRole("button", { name: /First model/ }).click();
    await page.getByRole("button", { name: /Second model second TEXT/ }).click();
    await expect(assistantDialog.getByRole("button", { name: /Second model/ })).toBeVisible();
    assert.deepEqual(modelUpdate, { assistantId: "a", modelId: "m2" });
    await expect(page.locator('[data-slot="popover-content"]')).toHaveCount(0);
    await page.keyboard.press("Escape");
    await expect(assistantDialog).not.toBeVisible();
    await page.getByRole("link", { name: "设置", exact: true }).click();
    await expect(page.getByRole("navigation", { name: "设置分类" }).getByRole("link")).toHaveCount(
      6,
    );
    await page.getByLabel("主题模式", { exact: true }).selectOption("dark");
    await page.getByLabel("主题配色", { exact: true }).selectOption("mono");
    await page.getByLabel("消息发送快捷键", { exact: true }).selectOption("shift");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect.poll(() => settings.displaySetting.sendOnEnter).toBe(false);
    await expect(page.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
    await page.getByRole("link", { name: "返回聊天" }).click();
    const composer = page.locator("textarea").first();
    await composer.fill("keyboard-test");
    await composer.press("Enter");
    assert.equal(messageCount, 0);
    await composer.press("Control+Enter");
    assert.equal(messageCount, 0);
    await composer.press("Shift+Enter");
    await expect.poll(() => messageCount).toBe(1);
    await page.getByRole("link", { name: "设置", exact: true }).click();
    await page.reload();
    await expect(page.getByLabel("主题模式", { exact: true })).toHaveValue("dark");
    await expect(page.locator("html")).toHaveClass(/dark/);
    await expect(page.getByLabel("主题配色", { exact: true })).toHaveValue("mono");
    await expect(page.getByRole("link", { name: "偏好设置", exact: true })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await page.screenshot({
      path: "build/settings-desktop.png",
      fullPage: true,
      animations: "disabled",
    });
    for (const title of ["模型与服务", "会话", "提示词与工作区", "连接与访问", "安全性"]) {
      await page.getByRole("link", { name: title, exact: true }).click();
      await expect(page.getByRole("heading", { name: title, exact: true })).toBeVisible();
    }
    await page.getByRole("link", { name: "模型与服务", exact: true }).click();
    settings.providers[0].name = "Updated on phone";
    managed.providers.revision = String(++version);
    managed.providers.values = { providers: settings.providers };
    for (const stream of streams)
      stream.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
    await expect(page.getByText("Updated on phone", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "添加供应商", exact: true }).click();
    page.once("dialog", d => d.accept());
    await page.getByRole("button", { name: "取消", exact: true }).click();
    assert.equal((managed.providers.values.providers as unknown[]).length, 1);
    await page.getByRole("button", { name: "添加供应商", exact: true }).click();
    await page.getByRole("button", { name: "下一步", exact: true }).click();
    await expect(page.getByRole("dialog")).toContainText("基础内容");
    await page.getByLabel("Base URL", { exact: false }).fill("https://example.com/v1");
    await page.getByLabel("API Key", { exact: false }).fill("test-only-key");
    await page.getByRole("button", { name: "下一步", exact: true }).click();
    await page.getByLabel("手动模型 ID", { exact: true }).fill("new-chat-model");
    await page.getByRole("button", { name: "添加模型", exact: true }).click();
    await page.getByRole("button", { name: "拉取模型", exact: true }).click();
    await page.getByLabel("discovered-image", { exact: true }).check();
    await page.keyboard.press("Escape");
    await page.getByLabel("discovered-image 模型类型", { exact: true }).selectOption("IMAGE");
    assert.equal((managed.providers.values.providers as unknown[]).length, 1);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole("dialog", { name: "模型列表", exact: true })).toHaveCount(0);
    const bounds = await page.getByRole("dialog", { name: "供应商配置 · 2/3", exact: true }).boundingBox();
    assert.ok(bounds && bounds.width <= 390 && bounds.height <= 844);
    await page.screenshot({ path: "../docs/local/provider-wizard-mobile.png", fullPage: true });
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.getByRole("button", { name: "下一步", exact: true }).click();
    await page.locator("summary").filter({ hasText: /^new-chat-model · 模型高级设置$/ }).click();
    await page.locator("details").filter({ has: page.locator("summary").filter({ hasText: /^new-chat-model · 模型高级设置$/ }) }).getByLabel("自定义请求体", { exact: true }).fill('[{"key":"temperature","value":0.5}]');
    await page.getByRole("button", { name: "完成配置", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(page.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
    assert.ok(JSON.stringify(managed.providers.values).includes("discovered-image"));
    assert.ok(JSON.stringify(managed.providers.values).includes("example.com"));
    assert.ok(JSON.stringify(managed.providers.values).includes("new-chat-model"));
    assert.ok(!JSON.stringify(managed.providers.values).includes("test-only-key"));
    await page.getByRole("button", { name: "搜索服务", exact: true }).click();
    await page.getByRole("button", { name: "添加搜索服务", exact: true }).click();
    await page
      .locator("summary")
      .filter({ hasText: /^tavily$/ })
      .click();
    await page.getByLabel("API 密钥", { exact: true }).fill("search-test-key");
    await page.getByLabel("默认搜索服务", { exact: true }).selectOption("1");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect.poll(() => managed.search.values.searchServiceSelected).toBe(1);
    await expect(page.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
    await page.getByLabel("新增搜索服务类型", { exact: true }).selectOption("1");
    await page.getByRole("button", { name: "添加搜索服务", exact: true }).click();
    await page.locator("summary").filter({ hasText: /^custom_js$/ }).click();
    await page.getByLabel("搜索接口 URL", { exact: false }).fill("https://search.test/api");
    page.once("dialog", d => d.accept());
    await page.getByLabel("启用可视化生成（修改自动写入脚本）", { exact: true }).check();
    await page.getByLabel("使用 POST JSON 请求", { exact: true }).check();
    await expect(page.getByLabel("搜索脚本", { exact: true })).toContainText('method: "POST"');
    await page.getByLabel("搜索接口 URL", { exact: false }).fill("file:///invalid");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    assert.equal((managed.search.values.searchServices as unknown[]).length, 2);
    await page.getByLabel("搜索接口 URL", { exact: false }).fill("https://search.test/api");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(page.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
    await page.getByRole("button", { name: "MCP", exact: true }).click();
    await page.getByRole("button", { name: "添加MCP 服务器", exact: true }).click();
    await page.locator("summary").filter({ hasText: /^sse$/ }).click();
    await page.getByLabel("名称", { exact: true }).fill("Test MCP");
    await page.getByLabel("服务地址", { exact: true }).fill("https://example.com/mcp");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(page.getByRole("button", { name: "保存配置", exact: true })).toBeDisabled();
    assert.ok(JSON.stringify(managed.mcp.values).includes("Test MCP"));
    await page.getByRole("button", { name: "Skills", exact: true }).click();
    await page.getByRole("button", { name: "新增 Skill", exact: true }).click();
    await page.getByLabel("Skill 名称", { exact: true }).fill("web-skill");
    await page.getByRole("button", { name: "保存 Skill", exact: true }).click();
    await expect.poll(() => !!skills["web-skill"]).toBe(true);
    await expect(page.getByRole("button", { name: "保存 Skill", exact: true })).toBeDisabled();
    await page.getByRole("link", { name: "会话", exact: true }).click();
    await page.getByLabel("标题最大长度", { exact: true }).fill("35");
    managed.conversations = {
      revision: String(++version),
      values: { ...managed.conversations.values, titleMaxLength: 46 },
    };
    publish();
    await expect(
      page.getByText("配置已变化；草稿已保留，保存前请核对或重新加载。", { exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel("标题最大长度", { exact: true })).toHaveValue("35");
    const conversationForm = page
      .locator("section")
      .filter({
        has: page.getByRole("heading", { name: "默认模型、提示词与会话生成", exact: true }),
      });
    await conversationForm.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(page.getByRole("alert").filter({ hasText: "配置已在手机修改" })).toBeVisible();
    page.once("dialog", (d) => d.accept());
    await conversationForm.getByRole("button", { name: "重新加载", exact: true }).click();
    await expect(page.getByLabel("标题最大长度", { exact: true })).toHaveValue("46");
    await page.getByLabel("标题提示词", { exact: true }).fill("Updated title prompt");
    await conversationForm.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(
      conversationForm.getByRole("button", { name: "保存配置", exact: true }),
    ).toBeDisabled();
    assert.equal(managed.conversations.values.titlePrompt, "Updated title prompt");
    await page.getByRole("link", { name: "安全性", exact: true }).click();
    await page.getByLabel("无操作自动退出", { exact: true }).selectOption("15");
    assert.equal(await page.evaluate(() => localStorage.getItem("rikkahub:web-idle-logout-minutes")), "15");
    assert.equal(await page.getByLabel("监听端口", { exact: true }).count(), 0);
    assert.equal(await page.getByRole("button", { name: "应用并重启服务", exact: true }).count(), 0);
    assert.equal(accessActions.includes("security/restart"), false);
    await page.reload();
    await expect(page.getByRole("button", { name: "退出当前登录" })).toBeVisible();
    // The settings page uses the same failure-safe logout as the chat sidebar.
    await page.route("**/api/auth/logout", (route) => route.abort());
    await page.getByRole("button", { name: "退出当前登录" }).click();
    await page.getByText("退出登录失败，请检查连接后重试").first().waitFor();
    assert.ok(await page.evaluate(() => sessionStorage.getItem("rikkahub:web-auth")));
    await page.unroute("**/api/auth/logout");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("link", { name: "偏好设置", exact: true }).click();
    await page.screenshot({
      path: "build/settings-mobile.png",
      fullPage: true,
      animations: "disabled",
      style: "[data-sonner-toaster] { visibility: hidden; }",
    });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.getByRole("link", { name: "返回聊天" }).click();
    await page.getByRole("button", { name: "Toggle Sidebar", exact: true }).click();
    await page.getByRole("button", { name: "助手配置", exact: true }).click();
    await expect(assistantDialog).toBeVisible();
    await page.screenshot({
      path: "build/assistant-settings-mobile.png",
      fullPage: true,
      animations: "disabled",
    });
    await page.keyboard.press("Escape");
    await page.setViewportSize({ width: 1280, height: 720 });
    await logout.click();
    await password.waitFor();
    assert.equal(await page.evaluate(() => sessionStorage.getItem("rikkahub:web-auth")), null);
    assert.ok(!(await context.cookies()).some((cookie) => cookie.name === "rikkahub_access"));
    assert.equal(await page.evaluate(async () => (await fetch("/api/media-probe")).status), 401);
    await password.fill("test-password");
    await page.locator("form button[type=submit]").click();
    await logout.waitFor();
    assert.equal(generation, 2);
    const origin = new URL(page.url()).origin;
    await page.goto(`${origin}/settings/unknown`);
    await expect(page.getByRole("heading", { name: "未找到设置板块" })).toBeVisible();
    await page.getByRole("link", { name: "前往偏好设置" }).click();
    await expect(page.getByLabel("主题模式", { exact: true })).toHaveValue("dark");
    const anonymous = await browser.newContext();
    const anonymousPage = await anonymous.newPage();
    await anonymousPage.goto(`${origin}/settings/security`);
    await expect(anonymousPage.locator('input[type="password"]')).toBeVisible();
    await anonymous.close();
    assert.deepEqual(pageErrors, []);
  } finally {
    await browser.close();
    server.closeAllConnections();
    await new Promise<void>((r) => server.close(() => r()));
  }
});

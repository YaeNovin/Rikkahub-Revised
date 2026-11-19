import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { chromium } from "@playwright/test";
import { renderToStaticMarkup } from "react-dom/server";
import { PreviewFrame } from "../app/components/workbench/preview-frame";

test("HTML and SVG scripts run without access to the parent origin", async () => {
  const previewOrigins: (string | undefined)[] = [];
  const server = createServer((request, response) => {
    if (request.url === "/api/probe") {
      previewOrigins.push(request.headers.origin);
      response.writeHead(403).end();
      return;
    }
    response.setHeader("Content-Type", "text/html");
    response.end("<!doctype html><title>Preview isolation test</title><body></body>");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const browser = await chromium.launch({
    channel:
      process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === "win32" ? "msedge" : undefined),
    headless: true,
  });
  try {
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
    await page.evaluate(() => sessionStorage.setItem("test-auth", "secret-sentinel"));
    for (const document of [
      `<button id="control" onclick="this.textContent='clicked'">ready</button><script>
        document.body.dataset.running = 'yes';
        try { parent.document.body.dataset.compromised = 'yes'; } catch { document.body.dataset.parentBlocked = 'yes'; }
        try { parent.sessionStorage.getItem('test-auth'); } catch { document.body.dataset.storageBlocked = 'yes'; }
        try { frameElement.removeAttribute('sandbox'); } catch { document.body.dataset.escapeBlocked = 'yes'; }
        fetch('/api/probe').catch(() => { document.body.dataset.apiBlocked = 'yes'; });
      </script>`,
      `<svg xmlns="http://www.w3.org/2000/svg" onload="document.body.dataset.running='yes';try{parent.document.body.dataset.compromised='yes'}catch{document.body.dataset.parentBlocked='yes'};try{parent.sessionStorage.getItem('test-auth')}catch{document.body.dataset.storageBlocked='yes'}"><circle cx="20" cy="20" r="10"/></svg>`,
    ]) {
      await page.locator("body").evaluate(
        (body, markup) => {
          body.innerHTML = markup;
        },
        renderToStaticMarkup(<PreviewFrame title="test preview" document={document} />),
      );
      const frame = page.frameLocator("iframe");
      await frame
        .locator("body[data-running=yes][data-parent-blocked=yes][data-storage-blocked=yes]")
        .waitFor();
      assert.equal(await page.locator("body").getAttribute("data-compromised"), null);
      assert.equal(await page.locator("iframe").getAttribute("sandbox"), "allow-scripts");
      if (document.startsWith("<button")) {
        await frame.locator("body[data-api-blocked=yes]").waitFor();
        assert.deepEqual(previewOrigins, ["null"]);
        await frame.locator("#control").click();
        assert.equal(await frame.locator("#control").textContent(), "clicked");
      } else {
        assert.equal(await frame.locator("circle").count(), 1);
      }
    }
  } finally {
    await browser.close();
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});

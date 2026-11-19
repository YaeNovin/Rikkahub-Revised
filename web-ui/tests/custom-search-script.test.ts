import assert from "node:assert/strict";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import { buildSearchScripts, defaultSearchBuilder } from "../app/services/custom-search-script";

test("generated synchronous search maps nested results, filters, deduplicates and encodes query", () => {
  const script = buildSearchScripts({ ...defaultSearchBuilder, url: "https://search.test/api?lang=zh", results: "data.results" });
  let requested = "";
  const result = runInNewContext(`${script.searchScript}; search('a&b 中', 1)`, { fetch: (url: string) => {
    requested = url;
    return { ok: true, json: () => ({ data: { results: [{ title: "bad", url: "javascript:alert(1)" }, { title: "first", url: "https://a.test", snippet: "answer" }, { title: "duplicate", url: "https://a.test" }] } }) };
  } });
  assert.equal(new URL(requested).searchParams.get("q"), "a&b 中");
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].text, "answer");
});
test("generated POST auth and scraping use native fetch contract without executing injected text", () => {
  const key = 'key"; throw Error("injected"); //';
  const scripts = buildSearchScripts({ ...defaultSearchBuilder, url: "https://search.test", post: true, auth: true, apiKey: key, scrape: true });
  runInNewContext(`${scripts.searchScript}; search('query', 5)`, { fetch: (_: string, options: { headers: Record<string, string>; body: string }) => {
    assert.equal(options.headers.Authorization, `Bearer ${key}`);
    assert.deepEqual(JSON.parse(options.body), { q: "query", limit: 5 });
    return { ok: true, json: () => ({ results: [] }) };
  } });
  const result = runInNewContext(`${scripts.scrapeScript}; scrape(['https://a.test'])`, { fetch: () => ({ ok: true, text: () => "body" }) });
  assert.equal(result.urls[0].content, "body");
  assert.throws(() => runInNewContext(`${scripts.scrapeScript}; scrape(['file:///secret'])`));
});
test("invalid visual config and upstream HTTP errors are actionable", () => {
  assert.throws(() => buildSearchScripts({ ...defaultSearchBuilder, url: "file:///tmp" }));
  const script = buildSearchScripts({ ...defaultSearchBuilder, url: "https://search.test" });
  assert.throws(() => runInNewContext(`${script.searchScript}; search('q', 5)`, { fetch: () => ({ ok: false, status: 401 }) }), /401/);
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { buildImageRequest, generateImages, imageRequestOptions, type ImageConnection, type ImageModel } from "../app/services/image-generation";

const model: ImageModel = { id: "m", name: "Test", modelId: "test", provider: "Mock", constraints: { supportsGeneration: true, supportsEdit: true, supportsOutputCount: true, maxOutputImages: 2, maxReferenceImages: 2, supportsSize: true, supportedSizes: ["1024x1024"], supportsCustomSize: false, sizeRequestField: "size", usesJsonImageEdit: false, usesGenerationEndpointForEdit: false, blockedImageOptionKeys: [], supportedOutputFormats: ["png", "jpeg"], supportedQualityValues: ["high"], supportedBackgroundValues: ["transparent"], seedRange: { min: 0, max: 10 } } };
const connection: ImageConnection = { type: "openai", baseUrl: "https://provider.example/v1", apiKey: "mock-key", modelId: "test-image", customHeaders: [], customBodies: [] };
test("browser request uses selected provider and protects required fields from custom overrides", async () => {
  const request = await buildImageRequest({ ...connection, customBodies: [{ key: "model", value: "wrong" }, { key: "prompt", value: "wrong" }] }, model, { prompt: "cat", count: 2, size: "1024x1024", quality: "high" }, []);
  assert.equal(request.url, "https://provider.example/v1/images/generations");
  assert.equal(new Headers(request.init.headers).get("Authorization"), "Bearer mock-key");
  assert.equal(request.init.credentials, "omit");
  assert.deepEqual(JSON.parse(String(request.init.body)), { model: "test-image", prompt: "cat", quality: "high", n: 2, size: "1024x1024" });
});
test("reference edit stays multipart in browser and rejects excessive references", async () => {
  const file = new File(["png"], "ref.png", { type: "image/png" });
  const request = await buildImageRequest(connection, model, { prompt: "edit", count: 1, size: "auto" }, [file]);
  assert.equal(request.url, "https://provider.example/v1/images/edits");
  assert.ok(request.init.body instanceof FormData);
  assert.equal(new Headers(request.init.headers).has("Content-Type"), false);
  assert.ok(request.init.body.get("image") instanceof File);
  await assert.rejects(buildImageRequest(connection, model, { prompt: "edit", count: 1, size: "auto" }, [file, file, file]), /参考图片/);
});
test("capability validation preserves valid options and rejects invalid seed", () => {
  assert.deepEqual(imageRequestOptions({ prompt: "x", count: 1, size: "auto", quality: "unsupported", background: "transparent", output_format: "jpeg" }, model.constraints, false), { output_format: "jpeg" });
  assert.throws(() => imageRequestOptions({ prompt: "x", count: 1, size: "auto", seed: 99 }, model.constraints, false), /seed/);
});
test("Google image request handles Vertex API-key endpoint and selected capabilities", async () => {
  const googleModel = { ...model, constraints: { ...model.constraints, supportedSizes: ["16:9"], supportedResolutionValues: ["2K"], supportedThinkingValues: ["HIGH"] } };
  const request = await buildImageRequest({ ...connection, type: "google", vertexAI: true }, googleModel, { prompt: "cat", count: 1, size: "16:9", resolution: "2K", thinking_level: "HIGH" }, []);
  assert.equal(request.url, "https://aiplatform.googleapis.com/v1/publishers/google/models/test-image:generateContent");
  const body = JSON.parse(String(request.init.body));
  assert.deepEqual(body.generationConfig.imageConfig, { aspectRatio: "16:9", imageSize: "2K" });
  assert.equal(new Headers(request.init.headers).get("x-goog-api-key"), "mock-key");
});
test("generation calls provider directly and decodes image without a phone generation request", async () => {
  const original = globalThis.fetch;
  const calls: string[] = [];
  globalThis.fetch = async input => { calls.push(String(input)); return Response.json({ data: [{ b64_json: btoa("image-bytes") }] }); };
  try {
    const result = await generateImages(connection, model, { prompt: "cat", count: 1, size: "auto" }, [], new AbortController().signal);
    assert.deepEqual(calls, ["https://provider.example/v1/images/generations"]);
    assert.equal(await result.images[0].text(), "image-bytes");
  } finally { globalThis.fetch = original; }
});
test("CORS failure is actionable and never silently proxies through phone", async () => {
  const original = globalThis.fetch;
  let count = 0;
  globalThis.fetch = async () => { count++; throw new TypeError("Failed to fetch"); };
  try { await assert.rejects(generateImages(connection, model, { prompt: "cat", count: 1, size: "auto" }, [], new AbortController().signal), /CORS/); assert.equal(count, 1); }
  finally { globalThis.fetch = original; }
});

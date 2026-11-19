export type Constraints = {
  supportsGeneration: boolean;
  supportsEdit: boolean;
  maxOutputImages: number;
  maxReferenceImages: number;
  supportsOutputCount: boolean;
  supportsSize: boolean;
  supportedSizes: string[];
  supportsCustomSize: boolean;
  sizeRequestField: string;
  usesJsonImageEdit: boolean;
  usesGenerationEndpointForEdit: boolean;
  blockedImageOptionKeys: string[];
  [key: string]: unknown;
};
export type ImageModel = {
  id: string;
  name: string;
  modelId: string;
  provider: string;
  constraints: Constraints;
};
export type ImageConnection = {
  type: "openai" | "google";
  baseUrl: string;
  apiKey: string;
  modelId: string;
  vertexAI?: boolean;
  customHeaders: { key: string; value: string; enabled?: boolean }[];
  customBodies: { key: string; value: unknown; enabled?: boolean }[];
};
export type ImageOptions = {
  prompt: string;
  count: number;
  size: string;
  [key: string]: string | number | boolean;
};
export type LocalImage = {
  parameters?: ImageOptions;
  resultText?: string;
  id: string;
  blob: Blob;
  modelId: string;
  model: string;
  prompt: string;
  editing: boolean;
  seed?: number;
  createdAt: number;
  folderId?: string;
};
const MAX_BYTES = 64 * 1024 * 1024;

async function readLimited(response: Response): Promise<Blob> {
  if (!response.body) throw new Error("供应商返回空响应");
  const reader = response.body.getReader();
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BYTES) throw new Error("图片响应超过 64 MiB");
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  return new Blob(chunks, {
    type: response.headers.get("content-type") || "application/octet-stream",
  });
}

export async function imageDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

export function imageRequestOptions(
  options: ImageOptions,
  c: Constraints,
  editing: boolean,
): Record<string, unknown> {
  let fields: Record<string, unknown> = {};
  const selects: Record<string, string> = {
    quality: "supportedQualityValues",
    background: "supportedBackgroundValues",
    resolution: "supportedResolutionValues",
    moderation: "supportedModerationValues",
    input_fidelity: "supportedInputFidelityValues",
    sampler: "supportedSamplerValues",
    style_preset: "supportedStylePresetValues",
    prompt_extend_mode: "supportedPromptEnhancementModes",
  };
  for (const [key, capability] of Object.entries(selects)) {
    if (key === "input_fidelity" && !editing) continue;
    if ((c[capability] as string[] | undefined)?.includes(String(options[key])))
      fields[key] = options[key];
  }
  if ((c.supportedOutputFormats as string[])?.includes(String(options.output_format))) {
    fields = ["url", "b64_json"].includes(String(options.output_format))
      ? { ...fields, response_format: options.output_format }
      : { ...fields, output_format: options.output_format };
  }
  if (
    fields.background === "transparent" &&
    !["png", "webp"].includes(String(fields.output_format))
  )
    delete fields.background;
  if (c.supportsOutputCompression && ["jpeg", "webp"].includes(String(fields.output_format)))
    fields.output_compression = Number(options.output_compression ?? 100);
  for (const [key, capability] of Object.entries({
    seed: "seedRange",
    steps: "stepsRange",
    guidance_scale: "guidanceScaleRange",
    safety_tolerance: "safetyToleranceRange",
  })) {
    const range = c[capability] as { min: number; max: number } | undefined;
    const value = options[key];
    if (range && value !== "" && value !== undefined) {
      const n = Number(value);
      if (!Number.isFinite(n) || n < range.min || n > range.max)
        throw new Error(`${key} 超出允许范围`);
      fields[key === "guidance_scale" ? String(c.guidanceScaleRequestField) : key] = n;
    }
  }
  if (c.supportsNegativePrompt && options.negative_prompt)
    fields.negative_prompt = options.negative_prompt;
  for (const [key, capability] of Object.entries({
    watermark: "supportsWatermark",
    enable_thinking: "supportsImageThinking",
  }))
    if (c[capability] && typeof options[key] === "boolean") fields[key] = options[key];
  if (c.promptEnhancementRequestField && typeof options.prompt_enhancement === "boolean")
    fields[String(c.promptEnhancementRequestField)] = options.prompt_enhancement;
  if (options.prompt_enhancement === false) {
    delete fields.prompt_extend_mode;
    delete fields.enable_thinking;
  }
  if (editing && fields.prompt_extend_mode === "agent") delete fields.prompt_extend_mode;
  if (c.sequentialImageMax && typeof options.sequential_image_generation === "boolean") {
    fields.sequential_image_generation = options.sequential_image_generation ? "auto" : "disabled";
    if (options.sequential_image_generation)
      fields.sequential_image_generation_options = {
        max_images: Math.min(
          Number(c.sequentialImageMax),
          Number(options.sequential_max_images || 1),
        ),
      };
  }
  if (
    (c.supportedPromptOptimizationModes as string[])?.includes(String(options.prompt_optimization))
  )
    fields.optimize_prompt_options = { mode: options.prompt_optimization };
  return fields;
}

export async function buildImageRequest(
  connection: ImageConnection,
  model: ImageModel,
  options: ImageOptions,
  references: File[],
): Promise<{ url: string; init: RequestInit }> {
  const c = model.constraints;
  const editing = references.length > 0;
  if (!options.prompt.trim() || options.prompt.length > 12000)
    throw new Error("请输入 1–12000 字的图片提示词");
  if (!Number.isInteger(options.count) || options.count < 1 || options.count > c.maxOutputImages)
    throw new Error("图片数量超出模型范围");
  if (editing && (!c.supportsEdit || references.length > c.maxReferenceImages))
    throw new Error("参考图片数量超出模型支持范围");
  if (
    references.some(
      (file) =>
        file.size > 32 * 1024 * 1024 ||
        !["image/png", "image/jpeg", "image/webp"].includes(file.type),
    )
  )
    throw new Error("参考图片需为 PNG、JPEG、WebP，且每张不超过 32 MiB");
  const base = (
    connection.type === "google" && connection.vertexAI
      ? "https://aiplatform.googleapis.com/v1"
      : connection.baseUrl
  ).replace(/\/+$/, "");
  const parsedUrl = new URL(base);
  if (!["http:", "https:"].includes(parsedUrl.protocol) || parsedUrl.username || parsedUrl.password)
    throw new Error("供应商地址无效");
  const headers = new Headers();
  for (const header of connection.customHeaders ?? [])
    if (header.enabled !== false && header.key.trim()) headers.set(header.key.trim(), header.value);
  const custom: Record<string, unknown> = {};
  for (const field of connection.customBodies ?? [])
    if (
      field.enabled !== false &&
      field.key.trim() &&
      !c.blockedImageOptionKeys.includes(field.key.toLowerCase())
    )
      custom[field.key.trim()] = field.value;
  let url: string;
  let body: BodyInit;
  const referenceData = async () => Promise.all(references.map(imageDataUrl));
  if (connection.type === "google") {
    headers.set("x-goog-api-key", connection.apiKey);
    const imageConfig: Record<string, unknown> = {};
    if (c.supportedSizes.includes(options.size) && options.size !== "auto")
      imageConfig.aspectRatio = options.size;
    if ((c.supportedResolutionValues as string[])?.includes(String(options.resolution)))
      imageConfig.imageSize = options.resolution;
    const generationConfig: Record<string, unknown> = {
      responseModalities: options.include_text ? ["TEXT", "IMAGE"] : ["IMAGE"],
      imageConfig,
    };
    if ((c.supportedThinkingValues as string[])?.includes(String(options.thinking_level)))
      generationConfig.thinkingConfig = { thinkingLevel: options.thinking_level };
    const parts: unknown[] = [{ text: options.prompt.trim() }];
    for (const data of await referenceData())
      parts.push({
        inlineData: { mimeType: data.slice(5, data.indexOf(";")), data: data.split(",")[1] },
      });
    const request: Record<string, unknown> = {
      ...custom,
      contents: [{ role: "user", parts }],
      generationConfig,
    };
    if (c.supportsSafetySettings) {
      const safety = [
        "HARASSMENT",
        "HATE_SPEECH",
        "SEXUALLY_EXPLICIT",
        "DANGEROUS_CONTENT",
      ].flatMap((key) =>
        options[`safety_${key}`]
          ? [{ category: `HARM_CATEGORY_${key}`, threshold: options[`safety_${key}`] }]
          : [],
      );
      if (safety.length) request.safetySettings = safety;
    }
    if (c.supportsImageSearchGrounding && options.image_search)
      request.tools = [{ googleSearch: { searchTypes: { webSearch: {}, imageSearch: {} } } }];
    else if (c.supportsWebSearchGrounding && options.web_search)
      request.tools = [{ googleSearch: {} }];
    url = `${base}/${connection.vertexAI ? "publishers/google/" : ""}models/${encodeURIComponent(connection.modelId)}:generateContent`;
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(request);
  } else {
    headers.set("Authorization", `Bearer ${connection.apiKey}`);
    const fields: Record<string, unknown> = {
      ...custom,
      ...imageRequestOptions(options, c, editing),
      model: connection.modelId,
      prompt: options.prompt.trim(),
    };
    if (c.supportsOutputCount) fields.n = options.count;
    if (c.supportsSize && options.size !== "auto" && options.size.trim()) {
      if (!c.supportedSizes.includes(options.size) && !c.supportsCustomSize)
        throw new Error("不支持所选尺寸");
      if (!c.supportedSizes.includes(options.size) && c.customSizeMultiple) {
        const match = /^(\d+)x(\d+)$/.exec(options.size);
        const w = Number(match?.[1]),
          h = Number(match?.[2]),
          pixels = w * h;
        if (
          !w ||
          !h ||
          w % Number(c.customSizeMultiple) ||
          h % Number(c.customSizeMultiple) ||
          (c.customSizeMaxDimension && Math.max(w, h) > Number(c.customSizeMaxDimension)) ||
          (c.customSizeMinPixels && pixels < Number(c.customSizeMinPixels)) ||
          (c.customSizeMaxPixels && pixels > Number(c.customSizeMaxPixels)) ||
          (c.customSizeMaxAspectRatio &&
            Math.max(w, h) / Math.min(w, h) > Number(c.customSizeMaxAspectRatio))
        )
          throw new Error("自定义尺寸不符合模型限制");
      }
      fields[c.sizeRequestField] = options.size;
    }
    url = `${base}/images/${editing && !c.usesGenerationEndpointForEdit ? "edits" : "generations"}`;
    if (editing && !c.usesJsonImageEdit && !c.usesGenerationEndpointForEdit) {
      const form = new FormData();
      for (const [key, value] of Object.entries(fields))
        if (!["image", "image[]", "stream"].includes(key))
          form.append(key, typeof value === "object" ? JSON.stringify(value) : String(value));
      references.forEach((file) =>
        form.append(references.length === 1 ? "image" : "image[]", file),
      );
      headers.delete("Content-Type");
      body = form;
    } else {
      delete fields.stream;
      if (editing) {
        const data = await referenceData();
        fields.image = c.usesGenerationEndpointForEdit ? data : { url: data[0], type: "image_url" };
        if (c.usesJsonImageEdit && data.length > 1) {
          delete fields.image;
          fields.images = data.map((url) => ({ url, type: "image_url" }));
        }
      }
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(fields);
    }
  }
  return {
    url,
    init: {
      method: "POST",
      headers,
      body,
      credentials: "omit",
      redirect: "error",
      cache: "no-store",
    },
  };
}

export async function generateImages(
  connection: ImageConnection,
  model: ImageModel,
  options: ImageOptions,
  references: File[],
  signal: AbortSignal,
): Promise<{ images: Blob[]; text: string }> {
  const request = await buildImageRequest(connection, model, options, references);
  let response: Response;
  try {
    response = await fetch(request.url, { ...request.init, signal });
  } catch (error) {
    if (signal.aborted) throw error;
    throw new Error(
      "浏览器无法直连供应商，请检查网络、HTTPS 混合内容及供应商 CORS 跨域许可。请求未转交手机执行。",
    );
  }
  if (!response.ok) throw new Error(`图片生成失败：HTTP ${response.status} ${response.statusText}`);
  const payload = JSON.parse(await (await readLimited(response)).text());
  const raw: { data?: string; mime?: string; url?: string }[] = [];
  const text: string[] = [];
  if (connection.type === "google")
    for (const candidate of payload.candidates ?? [])
      for (const part of candidate.content?.parts ?? []) {
        const data = part.inlineData ?? part.inline_data;
        if (data?.data)
          raw.push({ data: data.data, mime: data.mimeType ?? data.mime_type ?? "image/png" });
        if (part.text) text.push(part.text);
      }
  else
    for (const item of payload.data ?? []) {
      if (item.b64_json)
        raw.push({
          data: item.b64_json,
          mime: `image/${["jpeg", "webp"].includes(String(options.output_format)) ? options.output_format : "png"}`,
        });
      else if (item.url) raw.push({ url: item.url });
      if (item.revised_prompt) text.push(item.revised_prompt);
    }
  if (!raw.length) throw new Error("供应商未返回图片，可能被内容策略拦截或模型不支持该请求");
  const images: Blob[] = [];
  for (const item of raw) {
    signal.throwIfAborted();
    let blob: Blob;
    if (item.data) {
      const binary = atob(item.data);
      blob = new Blob([Uint8Array.from(binary, (c) => c.charCodeAt(0))], { type: item.mime });
    } else {
      const url = new URL(item.url!);
      if (!["http:", "https:"].includes(url.protocol)) throw new Error("供应商返回了无效图片地址");
      const result = await fetch(url, {
        signal,
        credentials: "omit",
        referrerPolicy: "no-referrer",
      });
      if (!result.ok) throw new Error(`图片下载失败：HTTP ${result.status}`);
      blob = await readLimited(result);
    }
    if (blob.size > 32 * 1024 * 1024) throw new Error("单张图片超过保存限制 32 MiB");
    images.push(blob);
  }
  return { images, text: text.join("\n") };
}

// Pending results survive refresh/disconnection. Provider credentials are never persisted here.
async function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("rikkahub-web-images", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("pending", { keyPath: "id" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
export async function pendingImages(): Promise<LocalImage[]> {
  const db = await database();
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction("pending").objectStore("pending").getAll();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally {
    db.close();
  }
}
export async function storePendingImage(image: LocalImage | string): Promise<void> {
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("pending", "readwrite");
      if (typeof image === "string") tx.objectStore("pending").delete(image);
      else tx.objectStore("pending").put(image);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

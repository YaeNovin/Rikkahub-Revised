import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { v4 as uuid } from "uuid";
import { Loader2, SlidersHorizontal, Paperclip } from "lucide-react";
import api from "~/services/api";
import { Button } from "~/components/ui/button";
import {
  generateImages,
  pendingImages,
  storePendingImage,
  type ImageConnection,
  type ImageModel,
  type ImageOptions,
  type LocalImage,
} from "~/services/image-generation";

import { Popover, PopoverTrigger, PopoverContent } from "~/components/ui/popover";

type Media = {
  parameters?: ImageOptions | null;
  resultText?: string;
  id: number;
  url: string;
  prompt: string;
  model: string;
  provider: string;
  width: number | null;
  height: number | null;
  folderId: string | null;
  seed: number | null;
  createdAt: number;
};
type Folder = { id: string; name: string };
const defaultOptions: ImageOptions = { prompt: "", count: 1, size: "auto" };
const selectOptions: [string, string, string][] = [
  ["quality", "质量", "supportedQualityValues"],
  ["output_format", "输出格式", "supportedOutputFormats"],
  ["background", "背景", "supportedBackgroundValues"],
  ["resolution", "分辨率", "supportedResolutionValues"],
  ["thinking_level", "思考等级", "supportedThinkingValues"],
  ["moderation", "内容审核", "supportedModerationValues"],
  ["input_fidelity", "输入保真度", "supportedInputFidelityValues"],
  ["sampler", "采样器", "supportedSamplerValues"],
  ["style_preset", "风格", "supportedStylePresetValues"],
  ["prompt_extend_mode", "提示词扩展模式", "supportedPromptEnhancementModes"],
  ["prompt_optimization", "提示词优化", "supportedPromptOptimizationModes"],
];
const inputClass = "w-full rounded-md border bg-background p-2 text-sm";

function LocalPreview({
  item,
  onSave,
  onDiscard,
}: {
  item: LocalImage;
  onSave: () => Promise<void>;
  onDiscard: () => Promise<void>;
}) {
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const value = URL.createObjectURL(item.blob);
    setUrl(value);
    return () => URL.revokeObjectURL(value);
  }, [item.blob]);
  return (
    <article className="space-y-2 rounded-xl border p-3">
      <a href={url} target="_blank" rel="noreferrer">
        <img src={url} alt={item.prompt} className="aspect-square w-full rounded object-contain" />
      </a>
      <p className="text-xs">已在浏览器生成 · 等待保存到软件</p>
      <p className="line-clamp-3 text-sm">{item.prompt}</p>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await onSave();
            } finally {
              setBusy(false);
            }
          }}
        >
          重试落库
        </Button>
        <Button size="sm" variant="outline" asChild>
          <a href={url} download={`rikkahub-${item.id}`}>
            下载
          </a>
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() => {
            if (window.confirm("移除此浏览器中尚未落库的图片？")) void onDiscard();
          }}
        >
          移除
        </Button>
      </div>
    </article>
  );
}

export default function ImagesPage() {
  const [models, setModels] = useState<ImageModel[]>([]);
  const [modelId, setModelId] = useState("");
  const [options, setOptions] = useState<ImageOptions>(defaultOptions);
  const [references, setReferences] = useState<File[]>([]);
  const [pending, setPending] = useState<LocalImage[]>([]);
  const [history, setHistory] = useState<Media[]>([]);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [folderId, setFolderId] = useState("");
  const [query, setQuery] = useState("");
  const [hasMore, setHasMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [resultText, setResultText] = useState("");
  const [preview, setPreview] = useState<Media | null>(null);
  const controller = useRef<AbortController | null>(null);
  const saving = useRef(new Set<string>());
  const mounted = useRef(true);
  const model = models.find((item) => item.id === modelId);
  const c = model?.constraints;
  const update = (key: string, value: string | number | boolean) =>
    setOptions((previous) => ({ ...previous, [key]: value }));
  async function refreshHistory(offset = 0) {
    const page = await api.get<Media[]>("images", {
      searchParams: { offset, query, ...(folderId ? { folderId } : {}) },
    });
    setHistory((previous) => (offset ? [...previous, ...page] : page));
    setHasMore(page.length === 40);
    setFolders(await api.get<Folder[]>("images/folders"));
  }
  useEffect(() => {
    mounted.current = true;
    try {
      const saved = JSON.parse(localStorage.getItem("rikkahub:image-options") || "null");
      if (saved) {
        setOptions(saved.options ?? defaultOptions);
        setModelId(saved.modelId ?? "");
      }
    } catch {
      /* Invalid browser preferences are ignored. */
    }
    void api
      .get<ImageModel[]>("images/models")
      .then((items) => {
        setModels(items);
        setModelId((previous) =>
          items.some((m) => m.id === previous) ? previous : (items[0]?.id ?? ""),
        );
      })
      .catch((e) => setError(String(e)));
    void pendingImages()
      .then(setPending)
      .catch((e) => setError(`无法读取本地待保存图片：${e}`));
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);
  useEffect(() => {
    const timer = setTimeout(() => {
      void refreshHistory().catch((e) => setError(String(e)));
    }, 250);
    return () => clearTimeout(timer);
  }, [folderId, query]);
  async function syncImage(item: LocalImage) {
    if (saving.current.has(item.id)) return;
    saving.current.add(item.id);
    try {
      const data = new FormData();
      data.append(
        "metadata",
        JSON.stringify({
          requestId: item.id,
          modelId: item.modelId,
          prompt: item.prompt,
          editing: item.editing,
          seed: item.seed,
          folderId: item.folderId ?? null,
          parameters: item.parameters,
          resultText: item.resultText,
        }),
      );
      data.append("image", item.blob, "generated-image");
      await api.postMultipart("images/import", data);
      await storePendingImage(item.id);
      setPending((previous) => previous.filter((image) => image.id !== item.id));
      await refreshHistory();
    } catch (e) {
      setError(`图片已在浏览器生成，但保存到软件失败。可下载或重试落库：${e}`);
    } finally {
      saving.current.delete(item.id);
    }
  }
  async function generate() {
    if (!model || busy) return;
    const abort = new AbortController();
    controller.current = abort;
    const timeout = setTimeout(
      () => abort.abort(new Error("图片生成超过 10 分钟，请检查供应商任务状态")),
      600000,
    );
    setBusy(true);
    setError("");
    setResultText("");
    setStatus("正在读取所选模型的连接配置…");
    try {
      localStorage.setItem("rikkahub:image-options", JSON.stringify({ modelId, options }));
      const connection = await api.post<ImageConnection>(
        `images/models/${model.id}/connection`,
        undefined,
        { signal: abort.signal },
      );
      setStatus("此设备正在向供应商请求并处理图片…");
      const result = await generateImages(connection, model, options, references, abort.signal);
      setResultText(result.text);
      for (const blob of result.images) {
        const item: LocalImage = {
          id: uuid(),
          blob,
          modelId: model.id,
          model: model.name,
          prompt: options.prompt,
          parameters: { ...options },
          resultText: result.text,
          editing: references.length > 0,
          createdAt: Date.now(),
          ...(options.seed !== undefined && options.seed !== ""
            ? { seed: Number(options.seed) }
            : {}),
          ...(folderId ? { folderId } : {}),
        };
        setPending((previous) => [...previous, item]);
        try {
          await storePendingImage(item);
        } catch {
          setError("本地存储空间不足，请立即下载生成的图片；刷新页面可能丢失未保存结果。");
        }
        setStatus("图片已在浏览器生成，正在回传软件落库…");
        await syncImage(item);
      }
      setStatus("生成完成");
    } catch (e) {
      if (mounted.current)
        setError(
          abort.signal.aborted
            ? "请求已停止；供应商可能仍在处理，已生成的本地结果会保留。"
            : String(e),
        );
    } finally {
      clearTimeout(timeout);
      if (mounted.current) {
        setBusy(false);
        controller.current = null;
      }
    }
  }
  async function useReference(item: Media) {
    try {
      const response = await fetch(item.url, { credentials: "same-origin" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const blob = await response.blob();
      setReferences((previous) => [
        ...previous,
        new File([blob], `reference-${item.id}`, { type: blob.type }),
      ]);
    } catch (e) {
      setError(String(e));
    }
  }
  async function remove(item: Media) {
    if (!window.confirm("从软件图库中删除这张图片？")) return;
    try {
      await api.delete(`images/${item.id}`);
      await refreshHistory();
    } catch (e) {
      setError(String(e));
    }
  }
  return (
    <main className="flex h-dvh flex-col overflow-hidden bg-background text-foreground">
      <header className="mx-auto flex w-full max-w-7xl shrink-0 flex-wrap items-center gap-2 p-3">
        <Button asChild variant="ghost">
          <Link to="/">返回聊天</Link>
        </Button>
        <h1 className="text-xl font-semibold">图片生成</h1>
        <Button asChild variant="outline">
          <Link to="/settings/models" state={{ returnTo: "/images" }}>管理图片模型</Link>
        </Button>
      </header>
      <div className="mx-auto w-full max-w-7xl min-h-0 flex-1 overflow-y-auto px-4 pb-4">
        <section className="min-w-0 space-y-4">
          {status && (
            <p role="status" className="text-sm">
              {status}
            </p>
          )}
          {resultText && <p className="whitespace-pre-wrap text-sm">{resultText}</p>}
          {error && (
            <p
              role="alert"
              className="rounded border border-destructive p-3 text-sm text-destructive"
            >
              {error}
            </p>
          )}
          {pending.length > 0 && (
            <>
              <h2 className="font-semibold">浏览器待保存结果（{pending.length}）</h2>
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {pending.map((item) => (
                  <LocalPreview
                    key={item.id}
                    item={item}
                    onSave={() => syncImage(item)}
                    onDiscard={async () => {
                      await storePendingImage(item.id);
                      setPending((previous) => previous.filter((p) => p.id !== item.id));
                    }}
                  />
                ))}
              </div>
            </>
          )}
          <h2 className="font-semibold">软件图库</h2>
          <div className="flex flex-wrap gap-2">
            <input
              aria-label="搜索图库"
              placeholder="搜索提示词"
              className="min-w-0 flex-1 rounded border p-2 text-sm"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <select
              aria-label="图库文件夹"
              value={folderId}
              onChange={(e) => setFolderId(e.target.value)}
              className="rounded border p-2 text-sm"
            >
              <option value="">全部图片</option>
              {folders.map((folder) => (
                <option key={folder.id} value={folder.id}>
                  {folder.name}
                </option>
              ))}
            </select>
            <Button
              variant="outline"
              onClick={async () => {
                const name = window.prompt("新建图库文件夹名称");
                if (!name?.trim()) return;
                try {
                  await api.post("images/folders", { name });
                  await refreshHistory();
                } catch (e) {
                  setError(String(e));
                }
              }}
            >
              新建文件夹
            </Button>
            {folderId && (
              <Button
                variant="ghost"
                onClick={async () => {
                  if (!window.confirm("解散此文件夹并保留图片？")) return;
                  try {
                    await api.delete(`images/folders/${folderId}`);
                    setFolderId("");
                  } catch (e) {
                    setError(String(e));
                  }
                }}
              >
                解散文件夹
              </Button>
            )}
          </div>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {history.map((item) => (
              <article key={item.id} className="space-y-2 rounded-xl border p-3">
                <button type="button" className="w-full" onClick={() => setPreview(item)}>
                  <img
                    src={item.url}
                    alt={item.prompt}
                    loading="lazy"
                    className="aspect-square w-full rounded object-contain"
                  />
                </button>
                <p className="line-clamp-3 text-sm">{item.prompt}</p>
                {item.resultText && (
                  <p className="whitespace-pre-wrap text-sm">{item.resultText}</p>
                )}
                {item.parameters && (
                  <details className="text-xs">
                    <summary>生成参数</summary>
                    <pre className="overflow-auto">{JSON.stringify(item.parameters, null, 2)}</pre>
                  </details>
                )}
                <p className="text-xs text-muted-foreground">
                  {item.model} · {item.width} × {item.height}
                </p>
                <div className="flex flex-wrap gap-1">
                  <Button size="sm" variant="outline" onClick={() => update("prompt", item.prompt)}>
                    复用提示词
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || !c?.supportsEdit}
                    onClick={() => void useReference(item)}
                  >
                    用作参考图
                  </Button>
                  <Button size="sm" variant="ghost" asChild>
                    <a href={item.url} download>
                      下载
                    </a>
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => void remove(item)}>
                    删除
                  </Button>
                </div>
                <select
                  aria-label={`移动图片 ${item.id}`}
                  value={item.folderId ?? ""}
                  className={inputClass}
                  onChange={async (e) => {
                    try {
                      await api.post(`images/${item.id}/folder`, {
                        folderId: e.target.value || null,
                      });
                      await refreshHistory();
                    } catch (error) {
                      setError(String(error));
                    }
                  }}
                >
                  <option value="">未归档</option>
                  {folders.map((folder) => (
                    <option key={folder.id} value={folder.id}>
                      {folder.name}
                    </option>
                  ))}
                </select>
              </article>
            ))}
          </div>
          {!history.length && <p className="py-8 text-center text-muted-foreground">暂无图片</p>}
          {hasMore && (
            <Button
              variant="outline"
              onClick={() => void refreshHistory(history.length).catch((e) => setError(String(e)))}
            >
              加载更多
            </Button>
          )}
        </section>
      </div>
      <section
        aria-label="图片输入框"
        className="mx-auto w-full max-w-4xl shrink-0 px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2"
      >
        <div className="relative flex flex-col gap-2 rounded-lg border bg-muted/50 p-2 shadow-sm transition-shadow focus-within:shadow-md focus-within:ring-1 focus-within:ring-ring">
          <fieldset disabled={busy}>
            <textarea
              aria-label="图片提示词"
              placeholder="描述要生成或编辑的图片"
              maxLength={12000}
              rows={3}
              value={options.prompt}
              onChange={(e) => update("prompt", e.target.value)}
              className="w-full resize-none bg-transparent p-2 text-sm outline-none"
            />
          </fieldset>
          <div className="flex flex-wrap items-center gap-2">
            <Popover>
              <PopoverTrigger asChild>
                <Button variant="ghost" size="sm" disabled={busy} aria-label="选择图片模型">
                  {model?.name || "选择模型"}
                </Button>
              </PopoverTrigger>
              <PopoverContent side="top" align="start">
                <fieldset disabled={busy} className="space-y-3">
                  {" "}
                  <label className="block text-sm">
                    图片模型
                    <select
                      aria-label="图片模型"
                      className={inputClass}
                      value={modelId}
                      onChange={(e) => {
                        setModelId(e.target.value);
                        setOptions({ ...defaultOptions, prompt: options.prompt });
                        setReferences([]);
                      }}
                    >
                      <option value="">请选择图片模型</option>
                      {models.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.provider} / {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  {!models.length && (
                    <p className="text-sm">请先登录并在“模型与服务”添加图片模型。</p>
                  )}
                </fieldset>
              </PopoverContent>
            </Popover>
            <Popover>
              <PopoverTrigger asChild>
                <Button variant="ghost" size="icon" disabled={busy} aria-label="图片参数">
                  <SlidersHorizontal className="size-4" />
                </Button>
              </PopoverTrigger>
              <PopoverContent
                side="top"
                className="max-h-[60dvh] w-80 max-w-[calc(100vw-2rem)] overflow-y-auto"
              >
                <fieldset disabled={busy} className="space-y-3">
                  {" "}
                  {c?.supportsOutputCount && (
                    <label className="block text-sm">
                      数量
                      <input
                        aria-label="图片数量"
                        type="number"
                        min={1}
                        max={c.maxOutputImages}
                        value={options.count}
                        onChange={(e) => update("count", Number(e.target.value))}
                        className={inputClass}
                      />
                    </label>
                  )}
                  {c?.supportsSize && (
                    <label className="block text-sm">
                      尺寸 / 宽高比
                      {c.supportsCustomSize ? (
                        <>
                          <input
                            aria-label="图片尺寸"
                            className={inputClass}
                            value={options.size}
                            list="image-sizes"
                            onChange={(e) => update("size", e.target.value)}
                          />
                          <datalist id="image-sizes">
                            <option value="auto" />
                            {c.supportedSizes.map((size) => (
                              <option key={size} value={size} />
                            ))}
                          </datalist>
                        </>
                      ) : (
                        <select
                          aria-label="图片尺寸"
                          className={inputClass}
                          value={options.size}
                          onChange={(e) => update("size", e.target.value)}
                        >
                          <option value="auto">自动</option>
                          {c.supportedSizes.map((size) => (
                            <option key={size} value={size}>
                              {size}
                            </option>
                          ))}
                        </select>
                      )}
                    </label>
                  )}
                  <details>
                    <summary className="cursor-pointer text-sm">高级参数</summary>
                    <div className="mt-3 space-y-3">
                      {selectOptions.map(([key, label, capability]) => {
                        const values = c?.[capability] as string[] | undefined;
                        return values?.length ? (
                          <label key={key} className="block text-sm">
                            {label}
                            <select
                              aria-label={label}
                              className={inputClass}
                              value={String(options[key] ?? "")}
                              onChange={(e) => update(key, e.target.value)}
                            >
                              <option value="">模型默认</option>
                              {values.map((value) => (
                                <option key={value} value={value}>
                                  {value}
                                </option>
                              ))}
                            </select>
                          </label>
                        ) : null;
                      })}
                      {[
                        ["seed", "种子", "seedRange"],
                        ["steps", "步数", "stepsRange"],
                        ["guidance_scale", "引导强度", "guidanceScaleRange"],
                        ["safety_tolerance", "安全容差", "safetyToleranceRange"],
                      ].map(([key, label, capability]) => {
                        const range = c?.[capability] as { min: number; max: number } | undefined;
                        return range ? (
                          <label key={key} className="block text-sm">
                            {label}
                            <input
                              aria-label={label}
                              className={inputClass}
                              type="number"
                              min={range.min}
                              max={range.max}
                              step={key === "guidance_scale" ? "any" : 1}
                              value={String(options[key] ?? "")}
                              onChange={(e) => update(key, e.target.value)}
                            />
                          </label>
                        ) : null;
                      })}
                      {Boolean(c?.supportsOutputCompression) && (
                        <label className="block text-sm">
                          输出压缩质量
                          <input
                            type="number"
                            min={0}
                            max={100}
                            className={inputClass}
                            value={Number(options.output_compression ?? 100)}
                            onChange={(e) => update("output_compression", Number(e.target.value))}
                          />
                        </label>
                      )}
                      {Boolean(c?.supportsNegativePrompt) && (
                        <textarea
                          aria-label="负面提示词"
                          placeholder="负面提示词"
                          className={inputClass}
                          value={String(options.negative_prompt ?? "")}
                          onChange={(e) => update("negative_prompt", e.target.value)}
                        />
                      )}
                      {[
                        ["watermark", "水印", "supportsWatermark"],
                        ["enable_thinking", "图片思考", "supportsImageThinking"],
                        ["prompt_enhancement", "提示词增强", "promptEnhancementRequestField"],
                        ["include_text", "同时返回文字", "supportsTextResponse"],
                        ["web_search", "网页搜索依据", "supportsWebSearchGrounding"],
                        ["image_search", "图片搜索依据", "supportsImageSearchGrounding"],
                        ["sequential_image_generation", "连续生图", "sequentialImageMax"],
                      ].map(([key, label, capability]) =>
                        c?.[capability] ? (
                          <label key={key} className="flex items-center gap-2 text-sm">
                            <input
                              type="checkbox"
                              checked={options[key] === true}
                              onChange={(e) => update(key, e.target.checked)}
                            />
                            {label}
                          </label>
                        ) : null,
                      )}
                      {Boolean(c?.sequentialImageMax) && (
                        <input
                          aria-label="连续生图数量上限"
                          type="number"
                          min={1}
                          max={Number(c?.sequentialImageMax)}
                          className={inputClass}
                          value={Number(options.sequential_max_images ?? 1)}
                          onChange={(e) => update("sequential_max_images", Number(e.target.value))}
                        />
                      )}
                      {Boolean(c?.supportsSafetySettings) &&
                        ["HARASSMENT", "HATE_SPEECH", "SEXUALLY_EXPLICIT", "DANGEROUS_CONTENT"].map(
                          (category) => (
                            <label key={category} className="block text-xs">
                              安全过滤：{category}
                              <select
                                className={inputClass}
                                value={String(options[`safety_${category}`] ?? "")}
                                onChange={(e) => update(`safety_${category}`, e.target.value)}
                              >
                                {[
                                  "",
                                  "OFF",
                                  "BLOCK_NONE",
                                  "BLOCK_ONLY_HIGH",
                                  "BLOCK_MEDIUM_AND_ABOVE",
                                  "BLOCK_LOW_AND_ABOVE",
                                ].map((value) => (
                                  <option key={value} value={value}>
                                    {value || "模型默认"}
                                  </option>
                                ))}
                              </select>
                            </label>
                          ),
                        )}
                    </div>
                  </details>
                </fieldset>
              </PopoverContent>
            </Popover>
            <Popover>
              <PopoverTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy || !c?.supportsEdit}
                  aria-label="导入参考图"
                >
                  <Paperclip className="size-4" />
                  {references.length || ""}
                </Button>
              </PopoverTrigger>
              <PopoverContent side="top" className="max-h-[50dvh] overflow-y-auto">
                <fieldset disabled={busy}>
                  {" "}
                  {c?.supportsEdit && (
                    <div className="space-y-2">
                      <label className="block text-sm">
                        参考图片（最多 {c.maxReferenceImages} 张）
                        <input
                          aria-label="参考图片"
                          className={inputClass}
                          type="file"
                          multiple
                          accept="image/png,image/jpeg,image/webp"
                          onChange={(e) => {
                            setReferences(Array.from(e.target.files ?? []));
                            e.target.value = "";
                          }}
                        />
                      </label>
                      {references.map((file, index) => (
                        <div
                          key={`${file.name}-${index}`}
                          className="flex items-center justify-between text-xs"
                        >
                          {file.name}
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              setReferences((previous) => previous.filter((_, i) => i !== index))
                            }
                          >
                            移除
                          </Button>
                        </div>
                      ))}
                    </div>
                  )}
                </fieldset>
              </PopoverContent>
            </Popover>
            <div className="ml-auto">
              {" "}
              <div className="flex gap-2">
                <Button
                  disabled={busy || !model || !options.prompt.trim()}
                  onClick={() => void generate()}
                >
                  {busy ? <Loader2 className="size-4 animate-spin" /> : null}
                  {references.length ? "编辑图片" : "生成图片"}
                </Button>
                {busy && (
                  <Button variant="outline" onClick={() => controller.current?.abort()}>
                    停止
                  </Button>
                )}
              </div>
            </div>
          </div>
        </div>
      </section>
      {preview && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="图片预览"
          className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-black/90 p-4"
          onClick={() => setPreview(null)}
        >
          <Button className="absolute right-4 top-4" onClick={() => setPreview(null)}>
            关闭
          </Button>
          <img
            src={preview.url}
            alt={preview.prompt}
            className="max-h-[85vh] max-w-full object-contain"
          />
          <p className="mt-3 max-w-3xl text-sm text-white">{preview.prompt}</p>
        </div>
      )}
    </main>
  );
}

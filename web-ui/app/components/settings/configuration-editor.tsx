import { useEffect, useState, type ReactNode } from "react";
import { v4 as uuid } from "uuid";
import { toast } from "sonner";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import { useUnsavedChanges } from "./unsaved-changes";
import { subscribeToEvent, EVENT_SETTINGS } from "~/services/events";
import { ProviderWizard } from "./provider-wizard";
import { CustomJsBuilder } from "./custom-js-builder";

export type Value = string | number | boolean | null | Value[] | { [key: string]: Value };
export type Config = { [key: string]: Value };
type Document = { revision: string; values: Config };
type Catalog = Record<string, Config[]>;
const control = "w-full min-w-0 rounded-md border bg-background px-3 py-2 text-sm text-foreground";
const labels: Record<string, string> = {
  providers: "模型供应商",
  models: "模型",
  searchServices: "搜索服务",
  mcpServers: "MCP 服务器",
  name: "名称",
  enabled: "启用",
  enable: "启用",
  apiKey: "API 密钥",
  baseUrl: "服务地址",
  type: "类型",
  chatCompletionsPath: "聊天接口路径",
  useResponseApi: "使用 Responses API",
  includeHistoryReasoning: "发送历史思考内容",
  balanceOption: "余额查询",
  apiPath: "查询路径",
  resultPath: "余额 JSON 路径",
  vertexAI: "使用 Vertex AI",
  useServiceAccount: "使用服务账号",
  privateKey: "服务账号私钥",
  serviceAccountEmail: "服务账号邮箱",
  location: "区域",
  projectId: "项目 ID",
  promptCaching: "提示词缓存",
  promptCacheTtl: "缓存有效期",
  modelId: "模型 ID",
  displayName: "显示名称",
  contextWindowTokens: "上下文容量（Token）",
  inputModalities: "输入模态",
  outputModalities: "输出模态",
  abilities: "模型能力",
  tools: "工具",
  customHeaders: "自定义请求头",
  customBodies: "自定义请求体",
  providerOverwrite: "独立供应商覆盖",
  commonOptions: "通用选项",
  headers: "请求头",
  needsApproval: "调用前需批准",
  description: "描述",
  url: "服务地址",
  username: "用户名",
  password: "密码",
  depth: "搜索深度",
  engines: "引擎",
  language: "语言",
  maxTokens: "最大 Token",
  maxTokensPerPage: "每页最大 Token",
  searchUrl: "搜索地址",
  scrapeUrl: "网页抓取地址",
  summary: "生成摘要",
  model: "模型",
  customUrl: "自定义地址",
  systemPrompt: "系统提示词",
  zone: "区域",
  kind: "搜索类型",
  baseUrlEnabled: "使用服务地址",
  endpoint: "接口路径",
  apiKeyEnabled: "使用 API 密钥",
  maxResults: "最大结果数",
  allowedXHandles: "允许的 X 账号",
  excludedXHandles: "排除的 X 账号",
  fromDate: "起始日期",
  toDate: "结束日期",
  extraParameters: "附加参数",
  timeoutSeconds: "超时（秒）",
  resultsPath: "结果列表路径",
  titlePath: "标题路径",
  urlPath: "链接路径",
  snippetPath: "摘要路径",
  searchScript: "搜索脚本",
  scrapeScript: "抓取脚本",
  searchCommonOptions: "搜索通用设置",
  resultSize: "结果数量",
  searchServiceSelected: "默认搜索服务",
  chatModelId: "默认聊天模型",
  fastModelId: "快速模型",
  titleModelId: "标题模型",
  translateModeId: "翻译模型",
  compressModelId: "上下文压缩模型",
  embeddingModelId: "嵌入模型",
  memoryExtractionModelId: "记忆模型",
  suggestionModelId: "建议回复模型",
  enableTitleGeneration: "自动生成标题",
  titleMaxLength: "标题最大长度",
  titlePrompt: "标题提示词",
  translatePrompt: "翻译提示词",
  translateThinkingBudget: "翻译思考预算",
  compressPrompt: "压缩提示词",
  suggestionPrompt: "建议回复提示词",
  enableSuggestion: "启用建议回复",
  suggestionCount: "建议数量",
  suggestionMaxLength: "建议最大长度",
  suggestionStyle: "建议风格",
  suggestionInsertMode: "建议插入方式",
  suggestionDisplayMode: "建议显示方式",
  suggestionOptions: "建议生成选项",
  enableGenerationRetry: "生成失败自动重连 / 重试",
  generationRetryMaxRetries: "最大重试次数",
  generationRetryInitialIntervalSeconds: "初始重试间隔（秒）",
  generationRetryMaxDurationSeconds: "最长重试时间（秒）",
  enableRollingContextCompression: "自动压缩上下文",
  rollingContextCompressionThresholdTokens: "压缩阈值（Token，至少 4000）",
  enabledSkills: "启用的 Skills",
  webServerPort: "监听端口",
  webServerLocalhostOnly: "仅允许手机本机访问",
  webServerJwtEnabled: "启用访问密码",
  webServerAccessPassword: "访问密码",
  sendOnEnter: "消息发送快捷键",
  value: "值",
  key: "字段名",
  trigger: "触发方式",
  intervalSeconds: "建议生成间隔（秒）",
  contextBudget: "建议上下文预算",
  includeSummary: "包含会话摘要",
  includeMemory: "包含记忆",
  goal: "建议目标",
  balancedCategories: "平衡建议类别",
  previewBeforeInsert: "插入前预览",
  parameterForms: "启用参数表单",
  insertion: "插入方式",
  inputSchema: "工具参数定义",
};
const secretStrings = new Set([
  "apiKey",
  "privateKey",
  "password",
  "webServerAccessPassword",
  "searchScript",
  "scrapeScript",
]);
const privateJson = new Set([
  "headers",
  "customHeaders",
  "customBodies",
  "providerOverwrite",
  "extraParameters",
]);
const ranges: Record<string, [number, number]> = {
  webServerPort: [1024, 65535],
  titleMaxLength: [8, 80],
  suggestionCount: [1, 10],
  suggestionMaxLength: [10, 200],
  generationRetryMaxRetries: [1, 7],
  generationRetryInitialIntervalSeconds: [1, 30],
  generationRetryMaxDurationSeconds: [10, 300],
  resultSize: [1, 100],
  rollingContextCompressionThresholdTokens: [4000, 2147483647],
};
const enums: Record<string, string[]> = {
  promptCacheTtl: ["5m", "1h"],
  suggestionInsertMode: ["REPLACE", "APPEND"],
  suggestionDisplayMode: ["AUTO", "COMPACT", "TWO_ROW", "RICH"],
  suggestionStyle: ["BALANCED", "FOLLOW_UP", "ACTIONABLE", "CONCISE", "ROLEPLAY"],
  kind: ["WEB", "X", "CUSTOM"],
};

function JsonField({
  name,
  value,
  onChange,
}: {
  name: string;
  value: Value;
  onChange: (v: Value) => void;
}) {
  const [text, setText] = useState(value == null ? "" : JSON.stringify(value, null, 2));
  const [invalid, setInvalid] = useState(false);
  return (
    <div className="space-y-2">
      <textarea
        className={control + " min-h-28 font-mono"}
        aria-label={name}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          try {
            onChange(e.target.value.trim() ? JSON.parse(e.target.value) : null);
            setInvalid(false);
            e.target.setCustomValidity("");
          } catch {
            setInvalid(true);
            e.target.setCustomValidity("请输入有效 JSON");
          }
        }}
      />
      {invalid && <p className="text-sm text-destructive">请输入有效 JSON，修正前不能保存。</p>}
      <p className="text-xs text-muted-foreground">
        高级 JSON 配置。空白保持原值；清空列表请输入 []。请求头使用 name/value，MCP 请求头使用
        first/second，请求体使用 key/value。
      </p>
    </div>
  );
}

function Field({
  name,
  value,
  onChange,
  catalog,
  root,
  path,
}: {
  name: string;
  value: Value;
  onChange: (v: Value) => void;
  catalog: Catalog;
  root: Config;
  path: string;
}) {
  const settings = useSettingsStore((s) => s.settings);
  const title = labels[name] ?? name;
  const fieldId = `config-${path}`;
  const modelField = name.endsWith("ModelId") || name === "translateModeId";
  let child: ReactNode;
  if (name === "id" || name === "oauth" || name === "inputSchema") return null;
  if (name === "sendOnEnter")
    child = (
      <select
        id={fieldId}
        className={control}
        value={value ? "enter" : "shift"}
        onChange={(e) => onChange(e.target.value === "enter")}
      >
        <option value="enter">Enter</option>
        <option value="shift">Shift+Enter</option>
      </select>
    );
  else if (modelField) {
    const models =
      settings?.providers
        .filter((p) => p.enabled)
        .flatMap((p) =>
          p.models
            .filter((m) =>
              name === "embeddingModelId"
                ? m.type === "EMBEDDING"
                : name === "memoryExtractionModelId"
                  ? ["CHAT", "EMBEDDING"].includes(m.type)
                  : m.type === "CHAT",
            )
            .map((m) => ({ ...m, providerName: p.name })),
        ) ?? [];
    child = (
      <select
        id={fieldId}
        className={control}
        value={String(value ?? "")}
        onChange={(e) => onChange(e.target.value || null)}
      >
        {[
          "titleModelId",
          "suggestionModelId",
          "memoryExtractionModelId",
          "embeddingModelId",
        ].includes(name) && <option value="">跟随默认 / 未指定</option>}
        {value && !models.some((m) => m.id === value) && (
          <option value={String(value)}>当前配置的模型（暂不可用）</option>
        )}
        {models.map((m) => (
          <option key={m.id} value={m.id}>
            {m.providerName} / {m.displayName || m.modelId}
          </option>
        ))}
      </select>
    );
  } else if (name === "searchServiceSelected")
    child = (
      <select
        id={fieldId}
        className={control}
        value={String(value)}
        onChange={(e) => onChange(Number(e.target.value))}
      >
        {((root.searchServices as Config[]) ?? []).map((s, i) => (
          <option key={String(s.id)} value={i}>
            {String(s.name || s.type)} ({i + 1})
          </option>
        ))}
      </select>
    );
  else if (privateJson.has(name))
    child = (
      <div className="space-y-2">
        {name === "providerOverwrite" &&
        value &&
        typeof value === "object" &&
        !Array.isArray(value) &&
        !value.__clear ? (
          <Fields values={value} onChange={onChange} catalog={catalog} root={root} path={path} />
        ) : (
          <JsonField
            key={
              value && typeof value === "object" && !Array.isArray(value) && value.__clear === true
                ? "clear"
                : "edit"
            }
            name={title}
            value={value}
            onChange={onChange}
          />
        )}
        {name === "providerOverwrite" && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => onChange({ __clear: true })}
          >
            移除独立覆盖，跟随供应商
          </Button>
        )}
      </div>
    );
  else if (secretStrings.has(name))
    child = (
      <div className="space-y-2">
        <textarea
          id={fieldId}
          className={control + " min-h-20"}
          autoComplete="off"
          value={String(value ?? "")}
          placeholder="留空保持原配置；输入新值后替换"
          onChange={(e) => onChange(e.target.value || null)}
          style={
            name.toLowerCase().includes("script")
              ? {}
              : ({ WebkitTextSecurity: "disc" } as React.CSSProperties)
          }
        />
        <Button type="button" size="sm" variant="outline" onClick={() => onChange("")}>
          清空此项
        </Button>
        <p className="text-xs text-muted-foreground">
          {value === "" ? "保存后将清空" : "原内容不回显"}
        </p>
      </div>
    );
  else if (name === "model" && value === null)
    child = (
      <div className="rounded-lg border p-3 space-y-3">
        <Fields
          values={{ enabled: false, value: "" }}
          onChange={onChange}
          catalog={catalog}
          root={root}
          path={path}
        />
      </div>
    );
  else if (typeof value === "boolean")
    child = (
      <input
        id={fieldId}
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
        className="size-5"
      />
    );
  else if (Array.isArray(value)) {
    if (["inputModalities", "outputModalities", "abilities"].includes(name))
      child = (
        <div className="flex flex-wrap gap-4">
          {(name === "abilities" ? ["TOOL", "REASONING"] : ["TEXT", "IMAGE", "VIDEO"]).map(
            (item) => (
              <label key={item} className="flex gap-2">
                <input
                  type="checkbox"
                  checked={value.includes(item)}
                  onChange={(e) =>
                    onChange(e.target.checked ? [...value, item] : value.filter((v) => v !== item))
                  }
                />
                {item}
              </label>
            ),
          )}
        </div>
      );
    else if (name === "enabledSkills") child = <SkillsChoice value={value} onChange={onChange} />;
    else
      child = (
        <ArrayEditor
          name={name}
          value={value}
          onChange={onChange}
          catalog={catalog}
          root={root}
          path={path}
        />
      );
  } else if (value && typeof value === "object")
    child = (
      <div className="space-y-4 rounded-lg border p-3">
        <Fields values={value} onChange={onChange} catalog={catalog} root={root} path={path} />
      </div>
    );
  else if (
    enums[name] ||
    (name === "type" && ["CHAT", "EMBEDDING", "IMAGE"].includes(String(value)))
  )
    child = (
      <select
        id={fieldId}
        className={control}
        value={String(value)}
        onChange={(e) => onChange(e.target.value)}
      >
        {(enums[name] ?? ["CHAT", "EMBEDDING", "IMAGE"]).map((v) => (
          <option key={v}>{v}</option>
        ))}
      </select>
    );
  else if (name === "type")
    child = <span className="text-sm text-muted-foreground">{String(value)}</span>;
  else if (
    typeof value === "number" ||
    ["contextWindowTokens", "maxTokens", "maxTokensPerPage"].includes(name)
  )
    child = (
      <input
        id={fieldId}
        className={control}
        type="number"
        value={value == null ? "" : String(value)}
        min={ranges[name]?.[0]}
        max={ranges[name]?.[1]}
        step="1"
        onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
      />
    );
  else if (name.toLowerCase().includes("prompt") || String(value ?? "").includes("\n"))
    child = (
      <textarea
        id={fieldId}
        className={control + " min-h-36"}
        value={String(value ?? "")}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  else
    child = (
      <input
        id={fieldId}
        className={control}
        value={String(value ?? "")}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  return (
    <div className="min-w-0 space-y-2">
      <label htmlFor={fieldId} className="block text-sm font-medium">
        {title}
      </label>
      {child}
    </div>
  );
}

function Fields({
  values,
  onChange,
  catalog,
  root = values,
  path = "root",
}: {
  values: Config;
  onChange: (v: Config) => void;
  catalog: Catalog;
  root?: Config;
  path?: string;
}) {
  return (
    <>
      {values.type === "custom_js" && (
        <CustomJsBuilder onChange={(v) => onChange({ ...values, ...v })} />
      )}
      {Object.entries(values).map(([name, value]) => (
        <Field
          key={name}
          name={name}
          value={value}
          onChange={(v) => {
            const next = { ...values, [name]: v };
            if (
              name === "searchServices" &&
              Array.isArray(v) &&
              Array.isArray(values.searchServices)
            ) {
              const selected = (
                values.searchServices[Number(values.searchServiceSelected)] as Config | undefined
              )?.id;
              next.searchServiceSelected = Math.max(
                0,
                v.findIndex(
                  (item) =>
                    !!item &&
                    typeof item === "object" &&
                    !Array.isArray(item) &&
                    item.id === selected,
                ),
              );
            }
            onChange(next);
          }}
          catalog={catalog}
          root={root}
          path={`${path}-${name}`}
        />
      ))}
    </>
  );
}

function ArrayEditor({
  name,
  value,
  onChange,
  catalog,
  root,
  path,
}: {
  name: string;
  value: Value[];
  onChange: (v: Value[]) => void;
  catalog: Catalog;
  root: Config;
  path: string;
}) {
  const [templateIndex, setTemplateIndex] = useState(0);
  const templates =
    catalog[name] ??
    (name === "tools" && !path.includes("mcpServers")
      ? [{ type: "search" }, { type: "url_context" }, { type: "image_generation" }]
      : []);
  if (name === "providers")
    return (
      <ProviderWizard
        value={value as Config[]}
        templates={templates}
        modelTemplate={
          catalog.models?.[0] ?? {
            modelId: "",
            type: "CHAT",
            displayName: "",
            customHeaders: [],
            customBodies: [],
            abilities: [],
            tools: [],
            inputModalities: ["TEXT"],
            outputModalities: ["TEXT"],
            contextWindowTokens: null,
            providerOverwrite: null,
          }
        }
        onChange={onChange}
        fields={(v, change, identity) => (
          <Fields
            values={v}
            onChange={change}
            catalog={catalog}
            root={root}
            path={`${path}-wizard-${identity}`}
          />
        )}
      />
    );
  return (
    <div className="space-y-3">
      {value.map((item, i) => (
        <details
          key={
            typeof item === "object" && item && !Array.isArray(item)
              ? String(item.id ?? item.name ?? i)
              : i
          }
          className="rounded-lg border p-3"
        >
          <summary className="cursor-pointer break-words text-sm font-medium">
            {typeof item === "object" && item && !Array.isArray(item)
              ? String(
                  item.name ||
                    item.displayName ||
                    item.modelId ||
                    item.type ||
                    `${labels[name] ?? name} ${i + 1}`,
                )
              : String(item)}
          </summary>
          <div className="mt-4 space-y-4">
            {item && typeof item === "object" && !Array.isArray(item) ? (
              <Fields
                values={item}
                onChange={(v) => onChange(value.map((entry, j) => (i === j ? v : entry)))}
                catalog={catalog}
                root={root}
                path={`${path}-${i}`}
              />
            ) : (
              <input
                className={control}
                value={String(item)}
                onChange={(e) =>
                  onChange(value.map((entry, j) => (i === j ? e.target.value : entry)))
                }
              />
            )}
            {name === "providers" && (
              <DiscoverModels
                provider={item as Config}
                onChange={(v) => onChange(value.map((entry, j) => (i === j ? v : entry)))}
              />
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                if (window.confirm("从待保存配置中删除此项？保存后将更新此板块配置。"))
                  onChange(value.filter((_, j) => i !== j));
              }}
            >
              删除此项
            </Button>
          </div>
        </details>
      ))}
      {!!templates.length && (
        <div className="flex flex-wrap gap-2">
          <select
            className={control + " !w-auto"}
            aria-label={`新增${labels[name] ?? name}类型`}
            value={templateIndex}
            onChange={(e) => setTemplateIndex(Number(e.target.value))}
          >
            {templates.map((t, i) => (
              <option key={i} value={i}>
                {String(t.type ?? t.name ?? i)}
              </option>
            ))}
          </select>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              const t = structuredClone(templates[templateIndex]);
              if ("id" in t) t.id = uuid();
              onChange([...value, t]);
            }}
          >
            添加{labels[name] ?? name}
          </Button>
        </div>
      )}
    </div>
  );
}

function DiscoverModels({
  provider,
  onChange,
}: {
  provider: Config;
  onChange: (v: Config) => void;
}) {
  const [models, setModels] = useState<Config[] | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <div className="space-y-2">
      <Button
        type="button"
        variant="outline"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          try {
            setModels(await api.get<Config[]>(`settings/manage/providers/${provider.id}/models`));
          } catch (e) {
            toast.error(String(e));
          } finally {
            setBusy(false);
          }
        }}
      >
        从供应商获取模型（请先保存配置）
      </Button>
      {models && (
        <div className="max-h-60 space-y-2 overflow-auto">
          {models.length ? (
            models.map((m) => (
              <Button
                key={String(m.id)}
                type="button"
                variant="ghost"
                className="block max-w-full truncate"
                onClick={() => {
                  const current = provider.models as Config[];
                  if (!current.some((x) => x.modelId === m.modelId))
                    onChange({ ...provider, models: [...current, { ...m, id: uuid() }] });
                }}
              >
                {String(m.displayName || m.modelId)} ＋
              </Button>
            ))
          ) : (
            <p className="text-sm">未获取到聊天或嵌入模型</p>
          )}
        </div>
      )}
    </div>
  );
}

function SkillsChoice({ value, onChange }: { value: Value[]; onChange: (v: Value[]) => void }) {
  const [skills, setSkills] = useState<{ name: string }[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    void api
      .get<{ name: string }[]>("settings/manage/skills")
      .then(setSkills)
      .catch((e) => setError(String(e)));
  }, []);
  return (
    <div className="space-y-2">
      {error && <p role="alert">{error}</p>}
      {skills.map((skill) => (
        <label key={skill.name} className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={value.includes(skill.name)}
            onChange={(e) =>
              onChange(
                e.target.checked ? [...value, skill.name] : value.filter((x) => x !== skill.name),
              )
            }
          />
          {skill.name}
        </label>
      ))}
      {!skills.length && !error && (
        <p className="text-sm text-muted-foreground">尚无 Skills，可在模型与服务中添加。</p>
      )}
    </div>
  );
}

export function ConfigurationEditor({
  group,
  title,
  assistantId,
  fields,
  children,
  onSaved,
}: {
  group: string;
  title: string;
  assistantId?: string;
  fields?: string[];
  children?: ReactNode;
  onSaved?: (v: Config) => void;
}) {
  const [document, setDocument] = useState<Document | null>(null);
  const [draft, setDraft] = useState<Config | null>(null);
  const [catalog, setCatalog] = useState<Catalog>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [remoteChanged, setRemoteChanged] = useState(false);
  useUnsavedChanges(dirty);
  const [epoch, setEpoch] = useState(0);
  const path = `settings/manage/${group}${assistantId ? `?assistantId=${encodeURIComponent(assistantId)}` : ""}`;
  useEffect(() => {
    const abort = new AbortController();
    setError("");
    setDocument(null);
    setDraft(null);
    setDirty(false);
    void Promise.all([
      api.get<Document>(path, { signal: abort.signal }),
      ["providers", "search", "mcp"].includes(group)
        ? api.get<Catalog>("settings/manage/catalog", { signal: abort.signal })
        : Promise.resolve({}),
    ])
      .then(([doc, templates]) => {
        if (abort.signal.aborted) return;
        setDocument(doc);
        setDraft(doc.values);
        setCatalog(templates);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(String(e));
      });
    return () => abort.abort();
  }, [path, group, epoch]);
  useEffect(() => {
    const abort = new AbortController();
    const unsubscribe = subscribeToEvent(EVENT_SETTINGS, () => {
      void api
        .get<Document>(path, { signal: abort.signal })
        .then((next) => {
          if (abort.signal.aborted) return;
          if (dirty) {
            setRemoteChanged(next.revision !== document?.revision);
            return;
          }
          setDocument(next);
          setDraft(next.values);
          setRemoteChanged(false);
        })
        .catch(() => {
          /* Preserve loaded values and drafts during disconnects. */
        });
    });
    return () => {
      abort.abort();
      unsubscribe();
    };
  }, [path, dirty, document?.revision]);
  return (
    <section className="space-y-4 rounded-xl border bg-card p-5">
      <h2 className="font-semibold">{title}</h2>
      <p className="text-sm text-muted-foreground">
        {assistantId ? "此助手的配置" : "模型与服务共享；其他 Web 独立配置"} ·
        修改后点击保存。并发修改会被拦截，失败时保留草稿。
      </p>
      {remoteChanged && (
        <p role="status" className="text-sm text-amber-600">
          配置已变化；草稿已保留，保存前请核对或重新加载。
        </p>
      )}
      {error && (
        <p role="alert" className="break-words text-sm text-destructive">
          {error}
        </p>
      )}
      {!draft && !error && <p role="status">正在加载…</p>}
      {draft && (
        <form
          key={epoch}
          className="space-y-5"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!document || busy) return;
            if (
              group === "security" &&
              !window.confirm(
                "保存访问设置可能使当前登录失效。端口和访问范围需点击应用并重启后生效，是否保存？",
              )
            )
              return;
            setBusy(true);
            setError("");
            const values = Object.fromEntries(
              Object.entries(draft).filter(([key]) => !fields || fields.includes(key)),
            );
            try {
              const next = await api.post<Document>(path, { revision: document.revision, values });
              setDocument(next);
              setDraft(next.values);
              setDirty(false);
              setRemoteChanged(false);
              toast.success("配置已保存");
              onSaved?.(values);
            } catch (e) {
              setError(String(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          <fieldset disabled={busy} className="space-y-5">
            <Fields
              key={document?.revision}
              values={
                fields
                  ? Object.fromEntries(
                      Object.entries(draft).filter(([key]) => fields.includes(key)),
                    )
                  : draft
              }
              onChange={(v) => {
                setDraft({ ...draft, ...v });
                setDirty(true);
              }}
              catalog={catalog}
              root={draft}
              path={group}
            />
            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={!dirty}>
                {busy ? "保存中…" : "保存配置"}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  if (!dirty || window.confirm("放弃未保存修改并加载已保存的配置？"))
                    setEpoch((v) => v + 1);
                }}
              >
                重新加载
              </Button>
            </div>
          </fieldset>
        </form>
      )}
      {!draft && error && (
        <Button variant="outline" onClick={() => setEpoch((v) => v + 1)}>
          重试加载
        </Button>
      )}
      {children}
    </section>
  );
}

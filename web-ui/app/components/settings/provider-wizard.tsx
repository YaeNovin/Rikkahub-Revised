import { useRef, useState, type ReactNode } from "react";
import { v4 as uuid } from "uuid";
import { Download, Plus, Loader2 } from "lucide-react";
import api from "~/services/api";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogDescription } from "~/components/ui/dialog";
import { Popover, PopoverTrigger, PopoverContent } from "~/components/ui/popover";
import { useUnsavedChanges } from "./unsaved-changes";
import type { Config } from "./configuration-editor";

const control = "w-full min-w-0 rounded border bg-background p-2 text-sm";
type Props = {
  value: Config[];
  templates: Config[];
  modelTemplate: Config;
  onChange: (v: Config[]) => void;
  fields: (v: Config, change: (v: Config) => void, identity: string) => ReactNode;
};
export function ProviderWizard({ value, templates, modelTemplate, onChange, fields }: Props) {
  const [draft, setDraft] = useState<Config | null>(null);
  const [step, setStep] = useState(0);
  const [manual, setManual] = useState("");
  const [found, setFound] = useState<Config[]>([]);
  const [menu, setMenu] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const panel = useRef<HTMLDivElement>(null);
  const generation = useRef(0);
  useUnsavedChanges(draft !== null);
  const models = (draft?.models ?? []) as Config[];
  const patch = (v: Config) =>
    setDraft((previous) => (previous ? { ...previous, ...v } : previous));
  const open = (v: Config) => {
    generation.current++;
    setDraft(structuredClone(v));
    setStep(0);
    setManual("");
    setFound([]);
    setMenu(false);
    setError("");
    setBusy(false);
  };
  const close = () => {
    if (window.confirm("放弃弹窗中的修改？")) {
      generation.current++;
      setDraft(null);
    }
  };
  const add = (model: Config) => {
    if (!String(model.modelId ?? "").trim() || models.some((m) => m.modelId === model.modelId))
      return;
    patch({ models: [...models, { ...structuredClone(modelTemplate), ...model, id: uuid() }] });
    setManual("");
  };
  const valid = () => {
    const invalid = panel.current?.querySelector<HTMLInputElement>(":invalid");
    if (invalid) {
      invalid.reportValidity();
      return false;
    }
    if (step === 0) {
      try {
        const url = new URL(String(draft?.baseUrl));
        if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash)
          throw Error();
      } catch {
        setError("请输入有效的 HTTP/HTTPS Base URL");
        return false;
      }
    }
    setError("");
    return true;
  };
  return (
    <div className="space-y-3">
      {value.map((p) => (
        <div key={String(p.id)} className="flex flex-wrap items-center gap-2 rounded border p-3">
          <span className="min-w-0 flex-1 break-words">
            <span>{String(p.name)}</span> · {(p.models as Config[]).length} 个模型
          </span>
          <Button type="button" variant="outline" onClick={() => open(p)}>
            配置供应商
          </Button>
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              if (window.confirm("删除此供应商？保存配置后生效。"))
                onChange(value.filter((x) => x.id !== p.id));
            }}
          >
            删除
          </Button>
        </div>
      ))}
      <Button
        type="button"
        variant="outline"
        disabled={!templates.length}
        onClick={() =>
          open({
            ...structuredClone(templates[0]),
            id: uuid(),
            name: "",
            baseUrl: "",
            apiKey: "",
            models: [],
          })
        }
      >
        添加供应商
      </Button>
      <Dialog
        open={draft !== null}
        onOpenChange={(next) => {
          if (!next) close();
        }}
      >
        <DialogContent
          className="max-h-[90dvh] w-[calc(100vw-2rem)] overflow-y-auto sm:max-w-2xl"
          onKeyDown={(e) => {
            if (e.key === "Enter" && e.target instanceof HTMLInputElement) e.preventDefault();
          }}
        >
          <DialogTitle>供应商配置 · {step + 1}/3</DialogTitle>
          <DialogDescription>
            {["基础内容", "模型配置", "高级设置（均为选填）"][step]} · * 为必填项
          </DialogDescription>
          {draft && (
            <div ref={panel} className="space-y-4">
              {step === 0 && (
                <>
                  <label className="block">
                    接口类型
                    <select
                      className={control}
                      value={String(draft.type)}
                      onChange={(e) => {
                        const t = templates.find((t) => t.type === e.target.value)!;
                        setDraft({
                          ...structuredClone(t),
                          id: draft.id,
                          name: draft.name,
                          baseUrl: draft.baseUrl,
                          apiKey: draft.apiKey,
                          models,
                        });
                      }}
                    >
                      {templates.map((t) => (
                        <option key={String(t.type)} value={String(t.type)}>
                          {String(t.type)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="block">
                    供应商名称
                    <input
                      className={control}
                      value={String(draft.name ?? "")}
                      placeholder="选填，默认使用服务域名"
                      onChange={(e) => patch({ name: e.target.value })}
                    />
                  </label>
                  <label className="block">
                    * Base URL
                    <input
                      required
                      type="url"
                      className={control}
                      value={String(draft.baseUrl ?? "")}
                      onChange={(e) => patch({ baseUrl: e.target.value.trim() })}
                    />
                  </label>
                  <label className="block">
                    {draft.apiKey !== null && "* "}API Key
                    <input
                      required={draft.apiKey !== null}
                      type="password"
                      autoComplete="new-password"
                      className={control}
                      value={String(draft.apiKey ?? "")}
                      placeholder={draft.apiKey === null ? "已配置，留空保留" : "输入 API Key"}
                      onChange={(e) =>
                        patch({
                          apiKey:
                            e.target.value || (value.some((p) => p.id === draft.id) ? null : ""),
                        })
                      }
                    />
                  </label>
                </>
              )}
              {step === 1 && (
                <>
                  <div className="flex gap-2">
                    <input
                      aria-label="手动模型 ID"
                      placeholder="输入模型 ID"
                      className={control}
                      value={manual}
                      onChange={(e) => setManual(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          add({ modelId: manual.trim() });
                        }
                      }}
                    />
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      aria-label="添加模型"
                      disabled={!manual.trim()}
                      onClick={() => add({ modelId: manual.trim() })}
                    >
                      <Plus />
                    </Button>
                    <Popover open={menu} onOpenChange={setMenu}>
                      <PopoverTrigger asChild>
                        <Button
                          type="button"
                          variant="outline"
                          size="icon"
                          aria-label="拉取模型"
                          disabled={busy}
                          onClick={async (e) => {
                            e.preventDefault();
                            const current = generation.current;
                            setBusy(true);
                            setError("");
                            try {
                              const result = await api.post<Config[]>(
                                "settings/manage/providers/discover",
                                draft,
                                { timeout: 35000 },
                              );
                              if (generation.current === current) {
                                setFound(result);
                                setMenu(true);
                              }
                            } catch (e) {
                              if (generation.current === current) setError(String(e));
                            } finally {
                              if (generation.current === current) setBusy(false);
                            }
                          }}
                        >
                          {busy ? <Loader2 className="animate-spin" /> : <Download />}
                        </Button>
                      </PopoverTrigger>
                      <PopoverContent className="max-h-72 overflow-auto" aria-label="模型列表">
                        {!found.length && <p>供应商未返回可用模型，可手动添加。</p>}
                        {found.map((m) => (
                          <label
                            key={String(m.modelId)}
                            className="flex items-center gap-2 py-2 break-all"
                          >
                            <input
                              type="checkbox"
                              checked={models.some((x) => x.modelId === m.modelId)}
                              onChange={(e) =>
                                e.target.checked
                                  ? add(m)
                                  : patch({ models: models.filter((x) => x.modelId !== m.modelId) })
                              }
                            />
                            {String(m.modelId)}
                          </label>
                        ))}
                      </PopoverContent>
                    </Popover>
                  </div>
                  {models.map((m, i) => (
                    <div
                      key={String(m.id)}
                      className="flex flex-wrap items-center gap-2 rounded border p-2"
                    >
                      <span className="min-w-0 flex-1 break-all">{String(m.modelId)}</span>
                      <select
                        aria-label={`${m.modelId} 模型类型`}
                        className="rounded border bg-background p-2"
                        value={String(m.type)}
                        onChange={(e) =>
                          patch({
                            models: models.map((x, j) =>
                              i === j ? { ...x, type: e.target.value } : x,
                            ),
                          })
                        }
                      >
                        {["CHAT", "EMBEDDING", "IMAGE"].map((t) => (
                          <option key={t}>{t}</option>
                        ))}
                      </select>
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => patch({ models: models.filter((_, j) => i !== j) })}
                      >
                        移除
                      </Button>
                    </div>
                  ))}
                  {!models.length && (
                    <p className="text-sm text-muted-foreground">尚未添加模型，可以稍后配置。</p>
                  )}
                </>
              )}
              {step === 2 && (
                <>
                  <details>
                    <summary>供应商高级设置</summary>
                    <div className="space-y-3 pt-3">
                      {fields(
                        Object.fromEntries(
                          Object.entries(draft).filter(
                            ([k]) =>
                              !["id", "type", "name", "baseUrl", "apiKey", "models"].includes(k),
                          ),
                        ),
                        (v) => patch(v),
                        "provider",
                      )}
                    </div>
                  </details>
                  {models.map((m, i) => (
                    <details key={String(m.id)}>
                      <summary>{String(m.modelId)} · 模型高级设置</summary>
                      <div className="space-y-3 pt-3">
                        {fields(
                          Object.fromEntries(
                            Object.entries(m).filter(
                              ([k]) => !["id", "modelId", "type"].includes(k),
                            ),
                          ),
                          (v) =>
                            patch({ models: models.map((x, j) => (i === j ? { ...x, ...v } : x)) }),
                          String(m.id),
                        )}
                        <Button
                          type="button"
                          variant="outline"
                          onClick={() =>
                            patch({
                              models: models.map((x, j) =>
                                i === j
                                  ? {
                                      ...x,
                                      providerOverwrite: {
                                        ...structuredClone(
                                          templates.find((t) => t.type === draft.type)!,
                                        ),
                                        id: uuid(),
                                        name: String(draft.name || "模型供应商"),
                                        models: [],
                                      },
                                    }
                                  : x,
                              ),
                            })
                          }
                        >
                          添加供应商重写
                        </Button>
                      </div>
                    </details>
                  ))}
                </>
              )}
              {error && (
                <p role="alert" className="text-sm text-destructive">
                  {error}
                </p>
              )}
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" onClick={close}>
                  取消
                </Button>
                {step > 0 && (
                  <Button
                    type="button"
                    variant="outline"
                    disabled={busy}
                    onClick={() => setStep(step - 1)}
                  >
                    上一步
                  </Button>
                )}
                <Button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    if (!valid()) return;
                    if (step < 2) setStep(step + 1);
                    else {
                      const next = {
                        ...draft,
                        name:
                          String(draft.name ?? "").trim() ||
                          new URL(String(draft.baseUrl)).hostname,
                      };
                      onChange(
                        value.some((p) => p.id === draft.id)
                          ? value.map((p) => (p.id === draft.id ? next : p))
                          : [...value, next],
                      );
                      setDraft(null);
                    }
                  }}
                >
                  {step < 2 ? "下一步" : "完成配置"}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                完成后点击页面的“保存配置”同步到软件。
              </p>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

import { useEffect, useState } from "react";
import api from "~/services/api";
import { useUnsavedChanges, confirmSettingsNavigation } from "./unsaved-changes";
import { toast } from "sonner";
import { Button } from "~/components/ui/button";
import { ConfigurationEditor } from "./configuration-editor";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { readWebReconnectSettings, WEB_RECONNECT_KEY } from "~/services/web-reconnect-settings";

export function ModelServiceSettings() {
  const [tab, setTab] = useState("providers");
  const [status, setStatus] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  return (
    <div className="space-y-5">
      <nav aria-label="模型与服务分类" className="flex flex-wrap gap-2">
        {[
          ["providers", "供应商"],
          ["search", "搜索服务"],
          ["mcp", "MCP"],
          ["skills", "Skills"],
        ].map(([id, title]) => (
          <Button
            key={id}
            variant={tab === id ? "default" : "outline"}
            onClick={() => {
              if (confirmSettingsNavigation()) setTab(id);
            }}
          >
            {title}
          </Button>
        ))}
      </nav>
      {tab === "skills" ? (
        <SkillsSettings />
      ) : (
        <ConfigurationEditor
          key={tab}
          group={tab}
          title={
            { providers: "模型供应商与模型", search: "搜索服务配置", mcp: "MCP 配置" }[tab] ?? tab
          }
        >
          {tab === "providers" && (
            <p className="text-sm text-muted-foreground">
              管理聊天、嵌入与图片模型；视频模型在手机端保留。删除仍被默认模型或助手使用的模型前，请先调整引用。
            </p>
          )}
          {tab === "mcp" && (
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">
                保存后手机自动连接并发现工具。刷新后可设置工具开关与审批。OAuth
                授权在手机端完成，网页不读取令牌。
              </p>
              <Button
                variant="outline"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await api.post("settings/manage/mcp/sync");
                    setStatus(await api.get<Record<string, string>>("settings/manage/mcp/status"));
                  } catch (e) {
                    toast.error(String(e));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                同步工具与连接状态
              </Button>
              {Object.entries(status).map(([id, text]) => (
                <p key={id} className="break-all text-sm">
                  {id}：{text}
                </p>
              ))}
            </div>
          )}
        </ConfigurationEditor>
      )}
    </div>
  );
}

export function AssistantContextSettings() {
  return (
    <AssistantConfiguration
      title="自动压缩上下文"
      fields={["enableRollingContextCompression", "rollingContextCompressionThresholdTokens"]}
    />
  );
}

function AssistantConfiguration({ title, fields }: { title: string; fields: string[] }) {
  const { currentAssistant, assistants } = useCurrentAssistant();
  const [targetId, setTargetId] = useState(currentAssistant?.id ?? "");
  useEffect(() => {
    if (!targetId && currentAssistant) setTargetId(currentAssistant.id);
  }, [targetId, currentAssistant]);
  const target = assistants.find((a) => a.id === targetId);
  return (
    <div className="space-y-3">
      <label className="flex flex-wrap items-center gap-3 text-sm">
        配置助手
        <select
          aria-label={`${title}的助手`}
          className="rounded-md border bg-background p-2"
          value={targetId}
          onChange={(e) => {
            if (confirmSettingsNavigation()) setTargetId(e.target.value);
          }}
        >
          {assistants.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name || "未命名助手"}
            </option>
          ))}
        </select>
      </label>
      {target ? (
        <ConfigurationEditor
          key={target.id}
          group="assistant"
          assistantId={target.id}
          title={`${title} · ${target.name || "当前助手"}`}
          fields={fields}
        />
      ) : (
        <p className="text-sm">请选取配置助手。</p>
      )}
    </div>
  );
}

export function WebReconnectPreferences() {
  const [value, setValue] = useState(readWebReconnectSettings);
  return (
    <section className="space-y-4 rounded-xl border p-5">
      <h2 className="font-semibold">网页自动重连</h2>
      <p className="text-sm text-muted-foreground">
        仅此浏览器。控制消息与配置实时连接，和手机上的生成失败重试分别设置。
      </p>
      <label className="flex items-center gap-3">
        <input
          type="checkbox"
          checked={value.enabled}
          onChange={(e) => setValue({ ...value, enabled: e.target.checked })}
        />
        断线后自动重连
      </label>
      <label className="flex flex-wrap items-center gap-3">
        初始重连间隔（秒）
        <input
          aria-label="网页重连间隔"
          type="number"
          className="rounded-md border bg-background p-2"
          min={1}
          max={30}
          value={value.delaySeconds}
          onChange={(e) => setValue({ ...value, delaySeconds: Number(e.target.value) })}
        />
      </label>
      <Button
        variant="outline"
        onClick={() => {
          if (
            !Number.isInteger(value.delaySeconds) ||
            value.delaySeconds < 1 ||
            value.delaySeconds > 30
          ) {
            toast.error("重连间隔必须为 1–30 秒");
            return;
          }
          if (!confirmSettingsNavigation()) return;
          try {
            localStorage.setItem(WEB_RECONNECT_KEY, JSON.stringify(value));
            window.location.reload();
          } catch {
            toast.error("浏览器不允许保存本地设置");
          }
        }}
      >
        保存并重新连接
      </Button>
    </section>
  );
}

type Runtime = {
  running: boolean;
  port: number;
  localhostOnly: boolean;
  address: string | null;
  hostname: string | null;
  localNetworkPermission: boolean;
  batteryOptimizationsIgnored?: boolean;
  backgroundRestricted?: boolean;
  manufacturer?: string;
};
export function SecuritySettings() {
  const [runtime, setRuntime] = useState<Runtime | null>(null);
  const [message, setMessage] = useState("");
  const [idle, setIdle] = useState(() => typeof window === "undefined" ? "0" : localStorage.getItem("rikkahub:web-idle-logout-minutes") || "0");
  useEffect(() => { void api.get<Runtime>("settings/manage/runtime").then(setRuntime).catch(e => setMessage(String(e))); }, []);
  return <section className="space-y-4 rounded-xl border p-5">
    <h2 className="font-semibold">浏览器安全偏好</h2>
    <label>无操作自动退出 <select aria-label="无操作自动退出" value={idle} onChange={e => { setIdle(e.target.value); localStorage.setItem("rikkahub:web-idle-logout-minutes", e.target.value); }} className="rounded border p-2">
      <option value="0">关闭</option><option value="5">5 分钟</option><option value="15">15 分钟</option><option value="60">60 分钟</option>
    </select></label>
    <p className="text-sm text-muted-foreground">仅影响此浏览器。手机的密码、端口、监听范围与服务启停请在手机上管理。</p>
    {runtime && <p className="text-sm">服务：{runtime.running ? "运行中" : "已停止"} · {runtime.localhostOnly ? "仅本机" : "局域网"} · 端口 {runtime.port}</p>}
    {message && <p role="alert">{message}</p>}
  </section>;
}

type Skill = { name: string; description: string };
type SkillDocument = { name: string; content: string; revision: string };
export function SkillsSettings() {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [doc, setDoc] = useState<SkillDocument | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  useUnsavedChanges(dirty);
  const refresh = () => api.get<Skill[]>("settings/manage/skills").then(setSkills);
  useEffect(() => {
    void refresh().catch((e) => setError(String(e)));
  }, []);
  const leave = () => !dirty || window.confirm("放弃未保存的 Skill 修改？");
  return (
    <div className="space-y-5">
      <section className="space-y-4 rounded-xl border p-5">
        <h2 className="font-semibold">Skills</h2>
        <p className="text-sm text-muted-foreground">
          与手机共享 SKILL.md。编辑已有技能会保留附属文件，删除会移除整个技能及助手绑定。
        </p>
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          {skills.map((skill) => (
            <Button
              key={skill.name}
              variant="outline"
              title={skill.description}
              onClick={async () => {
                if (!leave()) return;
                try {
                  setDoc(
                    await api.get<SkillDocument>(
                      `settings/manage/skills/${encodeURIComponent(skill.name)}`,
                    ),
                  );
                  setIsNew(false);
                  setDirty(false);
                  setError("");
                } catch (e) {
                  setError(String(e));
                }
              }}
            >
              {skill.name}
            </Button>
          ))}
        </div>
        <Button
          variant="outline"
          onClick={async () => {
            if (!leave()) return;
            try {
              const response = await api.get<{ revision: string }>("settings/manage/skills-new");
              setDoc({
                name: "new-skill",
                content:
                  "---\nname: new-skill\ndescription: 描述这个技能的用途\n---\n\n在此编写技能内容。",
                revision: response.revision,
              });
              setIsNew(true);
              setDirty(true);
              setError("");
            } catch (e) {
              setError(String(e));
            }
          }}
        >
          新增 Skill
        </Button>
        {doc && (
          <form
            className="space-y-4"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              setError("");
              try {
                const result = await api.post<{ revision: string }>(
                  "settings/manage/skills/save",
                  doc,
                );
                setDoc({ ...doc, revision: result.revision });
                setIsNew(false);
                setDirty(false);
                await refresh();
                toast.success("Skill 已保存到手机");
              } catch (e) {
                setError(String(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            <fieldset disabled={busy} className="space-y-4">
              <label className="block space-y-2">
                <span>Skill 名称</span>
                <input
                  required
                  disabled={!isNew}
                  className="w-full rounded-md border bg-background p-2"
                  value={doc.name}
                  onChange={(e) => {
                    setDoc({
                      ...doc,
                      name: e.target.value,
                      content: doc.content.replace(/^name: .*$/m, `name: ${e.target.value}`),
                    });
                    setDirty(true);
                  }}
                />
              </label>
              <label className="block space-y-2">
                <span>SKILL.md 内容</span>
                <textarea
                  required
                  className="min-h-72 w-full rounded-md border bg-background p-3 font-mono text-sm"
                  value={doc.content}
                  onChange={(e) => {
                    setDoc({ ...doc, content: e.target.value });
                    setDirty(true);
                  }}
                />
              </label>
              <label className="block text-sm">
                从 Markdown 文件导入正文
                <input
                  className="mt-2 block max-w-full"
                  type="file"
                  accept=".md,text/markdown"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (!file) return;
                    if (file.size > 512000) {
                      setError("文件不能超过 512KB");
                      return;
                    }
                    try {
                      setDoc({ ...doc, content: await file.text() });
                      setDirty(true);
                    } catch (err) {
                      setError(String(err));
                    }
                  }}
                />
              </label>
              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={!dirty}>
                  保存 Skill
                </Button>
                {!isNew && (
                  <Button
                    type="button"
                    variant="outline"
                    onClick={async () => {
                      if (!window.confirm(`删除 ${doc.name} 的全部文件和助手绑定？`)) return;
                      setBusy(true);
                      try {
                        await api.post("settings/manage/skills/delete", doc);
                        setDoc(null);
                        setDirty(false);
                        await refresh();
                      } catch (e) {
                        setError(String(e));
                      } finally {
                        setBusy(false);
                      }
                    }}
                  >
                    删除 Skill
                  </Button>
                )}
              </div>
            </fieldset>
          </form>
        )}
      </section>
      <AssistantConfiguration title="Skills 绑定" fields={["enabledSkills"]} />
    </div>
  );
}

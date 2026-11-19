import { useState, type ReactNode } from "react";
import { Link, useLocation, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Palette, Server, MessagesSquare, FolderOpen, Plug, Shield } from "lucide-react";
import { toast } from "sonner";
import { useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import { CustomThemeDialog } from "~/components/custom-theme-dialog";
import { COLOR_THEMES, useTheme, type ColorTheme, type Theme } from "~/components/theme-provider";
import { logoutWebSession } from "~/services/api";
import type { Settings } from "~/types";
import { cn } from "~/lib/utils";
import { ConfigurationEditor } from "~/components/settings/configuration-editor";
import {
  ModelServiceSettings,
  AssistantContextSettings,
  SecuritySettings,
  WebReconnectPreferences,
} from "~/components/settings/service-settings";

const sections = [
  {
    id: "preferences",
    title: "偏好设置",
    icon: Palette,
    description: "调整当前浏览器的外观与语言。",
  },
  {
    id: "models",
    title: "模型与服务",
    icon: Server,
    description: "管理与手机同步的供应商、搜索服务、MCP 和 Skills。",
  },
  {
    id: "conversations",
    title: "会话",
    icon: MessagesSquare,
    description: "配置默认模型、提示词、重试、标题及当前助手的上下文压缩。",
  },
  {
    id: "prompts-workspace",
    title: "提示词与工作区",
    icon: FolderOpen,
    description: "查看提示词资源与工作区的管理入口。",
  },
  {
    id: "connection",
    title: "连接与访问",
    icon: Plug,
    description: "查看当前访问地址与连接方式。",
  },
  {
    id: "security",
    title: "安全性",
    icon: Shield,
    description: "查看访问保护并管理当前登录会话。",
  },
] as const;

function Card({ title, scope, children }: { title: string; scope: string; children: ReactNode }) {
  return (
    <section className="space-y-4 rounded-xl border bg-card p-5 text-card-foreground">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{title}</h2>
        <span className="rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground">
          {scope}
        </span>
      </div>
      {children}
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b pb-3 last:border-0 last:pb-0">
      <span className="text-sm">{label}</span>
      <div className="max-w-full break-words text-sm text-muted-foreground">{children}</div>
    </div>
  );
}

function Note({ children }: { children: ReactNode }) {
  return <p className="text-sm leading-relaxed text-muted-foreground">{children}</p>;
}

function ToggleValue({ value }: { value: unknown }) {
  return <>{typeof value === "boolean" ? (value ? "开启" : "关闭") : "未提供"}</>;
}

function Preferences() {
  const { theme, setTheme, colorTheme, setColorTheme, customThemeCss, setCustomThemeCss } =
    useTheme();
  const { i18n } = useTranslation();
  const [customOpen, setCustomOpen] = useState(false);
  const selectClass = "max-w-full rounded-md border bg-background px-3 py-2 text-foreground";
  return (
    <>
      <Card title="外观与语言" scope="仅当前浏览器 · 自动保存">
        <Row label="主题模式">
          <select
            aria-label="主题模式"
            className={selectClass}
            value={theme}
            onChange={(e) => setTheme(e.target.value as Theme)}
          >
            <option value="system">跟随系统</option>
            <option value="light">浅色</option>
            <option value="dark">深色</option>
          </select>
        </Row>
        <Row label="主题配色">
          <select
            aria-label="主题配色"
            className={selectClass}
            value={colorTheme}
            onChange={(e) => setColorTheme(e.target.value as ColorTheme)}
          >
            {COLOR_THEMES.map((value) => (
              <option key={value} value={value}>
                {
                  {
                    default: "默认",
                    claude: "Claude",
                    "t3-chat": "T3 Chat",
                    mono: "黑白",
                    bubblegum: "泡泡糖",
                    custom: "自定义",
                  }[value]
                }
              </option>
            ))}
          </select>
        </Row>
        <Row label="界面语言">
          <select
            aria-label="界面语言"
            className={selectClass}
            value={i18n.language.startsWith("zh") ? "zh-CN" : "en-US"}
            onChange={(e) => void i18n.changeLanguage(e.target.value)}
          >
            <option value="zh-CN">简体中文</option>
            <option value="en-US">English</option>
          </select>
        </Row>
        <Row label="自定义 CSS">
          <Button variant="outline" onClick={() => setCustomOpen(true)}>
            编辑自定义主题
          </Button>
        </Row>
        <Note>保留此浏览器已有偏好。配色与语言不修改手机设置；本次新增设置项目暂以中文显示。</Note>
      </Card>
      <CustomThemeDialog
        open={customOpen}
        onOpenChange={setCustomOpen}
        initialCss={customThemeCss}
        onSave={(css) => {
          setCustomThemeCss(css);
          setColorTheme("custom");
        }}
      />
    </>
  );
}

function Prompts({ settings }: { settings: Settings }) {
  return (
    <>
      <Card title="提示词资源" scope="Web 独立快照 · 只读">
        <Row label="模式注入">{settings.modeInjections?.length ?? 0}</Row>
        <Row label="世界书">{settings.lorebooks?.length ?? 0}</Row>
        <Row label="快捷消息">{settings.quickMessages?.length ?? 0}</Row>
        <Note>通过聊天输入区的扩展菜单选择已有资源。Web 使用首次启用时的独立副本，手机后续修改不会覆盖。</Note>
      </Card>
      <Card title="工作区" scope="手机授权 · 会话使用">
        <Note>
          工作区目录授权及文件操作模式暂需在手机端管理。助手调用工具产生的文件与预览继续在聊天工作台展示。
        </Note>
      </Card>
    </>
  );
}

function Connection() {
  return (
    <Card title="当前访问" scope="当前浏览器">
      <Row label="访问地址">{typeof window === "undefined" ? "—" : window.location.origin}</Row>
      <Row label="传输方式">
        {typeof window !== "undefined" && window.location.protocol === "https:" ? "HTTPS" : "HTTP"}
      </Row>
      <Note>
        网页使用当前地址连接手机 Web 服务。首次连接或更改端口后，请在手机端确认服务已启动。
      </Note>
      <Note>
        如连接中断，页面会尝试自动重连；持续无响应时请检查网络，并将手机上的应用切回前台。
      </Note>
      <Button variant="outline" onClick={() => window.location.reload()}>
        重新加载并连接
      </Button>
    </Card>
  );
}

function Security({ settings }: { settings: Settings }) {
  const [pending, setPending] = useState(false);
  async function logout() {
    setPending(true);
    try {
      await logoutWebSession();
      window.location.reload();
    } catch {
      toast.error("退出登录失败，请检查连接后重试");
    } finally {
      setPending(false);
    }
  }
  return (
    <Card title="访问保护" scope="手机配置 · 当前登录">
      <Row label="Web 密码保护">
        <ToggleValue value={settings.webServerJwtEnabled} />
      </Row>
      <Note>服务端访问控制由手机管理。退出登录会撤销当前浏览器会话，不修改手机配置。</Note>
      {settings.webServerJwtEnabled ? (
        <Button variant="outline" disabled={pending} onClick={() => void logout()}>
          {pending ? "正在退出…" : "退出当前登录"}
        </Button>
      ) : (
        <Note>当前未启用密码保护，无需退出登录。需要限制访问时，请在手机端启用 Web 密码。</Note>
      )}
    </Card>
  );
}

export default function SettingsPage() {
  const { section = "preferences" } = useParams();
  const location = useLocation();
  const settings = useSettingsStore((state) => state.settings);
  const active = sections.find((item) => item.id === section);
  const candidate: unknown = location.state?.returnTo;
  const returnTo =
    typeof candidate === "string" && (candidate === "/" || candidate === "/images" || /^\/c\/[^/?#]+$/.test(candidate))
      ? candidate
      : "/";
  return (
    <main className="min-h-svh bg-background text-foreground">
      <header className="border-b px-4 py-4">
        <div className="mx-auto flex max-w-6xl items-center gap-4">
          <Button asChild variant="ghost" size="sm">
            <Link to={returnTo}>
              <ArrowLeft className="size-4" />
              返回聊天
            </Link>
          </Button>
          <h1 className="text-lg font-semibold">设置</h1>
        </div>
      </header>
      <div className="mx-auto grid max-w-6xl gap-6 p-4 md:grid-cols-[210px_minmax(0,1fr)] md:p-6">
        <nav aria-label="设置分类" className="flex flex-wrap content-start gap-2 md:flex-col">
          {sections.map((item) => (
            <Link
              key={item.id}
              to={`/settings/${item.id}`}
              state={{ returnTo }}
              aria-current={active?.id === item.id ? "page" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-lg px-3 py-2.5 text-sm",
                active?.id === item.id ? "bg-primary text-primary-foreground" : "hover:bg-muted",
              )}
            >
              <item.icon className="size-4 shrink-0" />
              {item.title}
            </Link>
          ))}
        </nav>
        <div className="min-w-0 space-y-5">
          {active ? (
            <>
              <div>
                <h2 className="text-2xl font-semibold">{active.title}</h2>
                <p className="mt-2 text-sm text-muted-foreground">{active.description}</p>
              </div>
              {section === "preferences" ? (
                <>
                  <Preferences />
                  <ConfigurationEditor group="preferences" title="发送快捷键" />
                </>
              ) : section === "connection" ? (
                <Connection />
              ) : !settings ? (
                <div role="status" className="rounded-xl border p-5">
                  正在等待手机配置。若长时间未加载，请检查连接或登录状态。
                  <div className="mt-3">
                    <Button variant="outline" onClick={() => window.location.reload()}>
                      重新连接
                    </Button>
                  </div>
                </div>
              ) : section === "models" ? (
                <ModelServiceSettings />
              ) : section === "conversations" ? (
                <>
                  <ConfigurationEditor group="conversations" title="默认模型、提示词与会话生成" />
                  <AssistantContextSettings />
                  <WebReconnectPreferences />
                </>
              ) : section === "prompts-workspace" ? (
                <Prompts settings={settings} />
              ) : (
                <>
                  <SecuritySettings />
                  <Security settings={settings} />
                </>
              )}
            </>
          ) : (
            <>
              <h2 className="text-xl font-semibold">未找到设置板块</h2>
              <Link className="underline" to="/settings/preferences" state={{ returnTo }}>
                前往偏好设置
              </Link>
            </>
          )}
        </div>
      </div>
    </main>
  );
}

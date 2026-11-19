import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import api from "~/services/api";
import { Button } from "~/components/ui/button";

type Usage = {
  usedTokens: number;
  capacityTokens: number | null;
  remainingTokens: number | null;
  percentage: number | null;
  measuredInputTokens: number | null;
  calibrated: boolean;
  basedOnRequest: boolean;
  breakdown: Record<string, number>;
  summary: string;
  processingStatus: string;
  compressing: boolean;
};
const labels: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  VIDEO: "视频",
  AUDIO: "音频",
  DOCUMENT: "文档",
  REASONING: "思考",
  TOOLS: "工具调用与结果",
  SUMMARY: "滚动摘要",
  SYSTEM: "系统提示与注入",
  FRAMING: "消息结构",
  TOOL_SCHEMA: "工具定义",
  OTHER: "其他输入与统计差额",
};

export function ContextStatus({ id, generating }: { id: string; generating: boolean }) {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [target, setTarget] = useState(2048);
  const [prompt, setPrompt] = useState("");
  const [liveStatus, setLiveStatus] = useState<string | null>(null);
  useEffect(() => {
    const listener = (event: Event) => {
      const detail = (event as CustomEvent<{ conversationId: string; status: string }>).detail;
      if (detail.conversationId === id) setLiveStatus(detail.status);
    };
    window.addEventListener("rikkahub:processing", listener);
    return () => window.removeEventListener("rikkahub:processing", listener);
  }, [id]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    setUsage(null);
    const refresh = async () => {
      try {
        const result = await api.get<Usage>(`conversations/${id}/context`, {
          signal: controller.signal,
        });
        if (
          !result ||
          typeof result.usedTokens !== "number" ||
          typeof result.breakdown !== "object"
        )
          throw new Error("当前服务未提供有效的上下文统计，请更新软件后重试");
        setUsage(result);
        setError("");
      } catch (e) {
        if (!controller.signal.aborted) setError(String(e));
      }
      if (!controller.signal.aborted) timer = setTimeout(refresh, generating || busy ? 1500 : 8000);
    };
    void refresh();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [id, generating, busy]);
  async function compress() {
    setBusy(true);
    setError("");
    try {
      await api.post(
        `conversations/${id}/compress`,
        { targetTokens: target, additionalPrompt: prompt },
        { timeout: 300000 },
      );
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }
  const processingStatus = liveStatus ?? usage?.processingStatus;
  const processing = busy || usage?.compressing || Boolean(processingStatus);
  return (
    <div className="mx-auto w-full max-w-3xl px-3 py-2 text-xs">
      {processing && (
        <div role="status" className="flex items-center gap-2 rounded-md bg-primary/10 p-2">
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
          {processingStatus || "正在压缩上下文，历史消息会保留…"}
        </div>
      )}
      <details>
        <summary className="cursor-pointer py-1">
          上下文：
          {usage
            ? `${usage.usedTokens.toLocaleString()} / ${usage.capacityTokens?.toLocaleString() ?? "容量未知"} Token${usage.percentage == null ? "" : `（${usage.percentage}%）`}`
            : "读取中…"}
        </summary>
        {usage && (
          <div className="space-y-2 rounded-md border p-3">
            <p>
              当前占用为估算。
              {usage.calibrated
                ? "已按供应商输入统计校准。"
                : usage.basedOnRequest
                  ? "基于实际请求快照。"
                  : "尚无可用请求快照。"}{" "}
              剩余：{usage.remainingTokens?.toLocaleString() ?? "未知"}
            </p>
            {usage.measuredInputTokens != null && (
              <p>最近请求输入（供应商统计）：{usage.measuredInputTokens.toLocaleString()} Token</p>
            )}
            {usage.capacityTokens && (
              <progress
                className="w-full"
                max={Math.max(usage.capacityTokens, usage.usedTokens)}
                value={usage.usedTokens}
              />
            )}
            <dl className="grid grid-cols-2 gap-1">
              {Object.entries(usage.breakdown).map(([key, value]) => (
                <div key={key}>
                  {labels[key] ?? key}：{value.toLocaleString()}
                </div>
              ))}
            </dl>
            {usage.summary && (
              <details>
                <summary>当前滚动摘要</summary>
                <p className="whitespace-pre-wrap">{usage.summary}</p>
              </details>
            )}
            <label className="block">
              摘要目标 Token{" "}
              <input
                aria-label="摘要目标 Token"
                type="number"
                min={256}
                max={32000}
                value={target}
                onChange={(e) => setTarget(Number(e.target.value))}
                className="rounded border p-1"
              />
            </label>
            <textarea
              aria-label="压缩附加要求"
              placeholder="压缩附加要求（可选）"
              maxLength={8000}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              className="w-full rounded border p-2"
            />
            <Button
              size="sm"
              disabled={generating || processing || target < 256 || target > 32000}
              onClick={() => void compress()}
            >
              手动压缩上下文
            </Button>
          </div>
        )}
      </details>
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

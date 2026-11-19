import { useEffect, useRef, useState } from "react";
import { Button } from "~/components/ui/button";
import {
  buildSearchScripts,
  defaultSearchBuilder,
  type SearchBuilder,
} from "~/services/custom-search-script";
import type { Config } from "./configuration-editor";

export function CustomJsBuilder({ onChange }: { onChange: (v: Config) => void }) {
  const [config, setConfig] = useState<SearchBuilder>({ ...defaultSearchBuilder });
  const [active, setActive] = useState(false);
  const [error, setError] = useState("");
  const validity = useRef<HTMLInputElement>(null);
  useEffect(() => {
    validity.current?.setCustomValidity(active ? error : "");
  }, [error, active]);
  const update = (next: SearchBuilder) => {
    setConfig(next);
    if (active) {
      try {
        onChange(buildSearchScripts(next));
        setError("");
      } catch (e) {
        setError(String(e));
      }
    }
  };
  return (
    <section className="space-y-3 rounded border p-3">
      <h3 className="font-medium">Custom JS 可视化配置</h3>
      <input
        ref={validity}
        className="sr-only"
        aria-label="可视化配置校验"
        tabIndex={-1}
        onChange={() => {}}
        value=""
      />
      <p className="text-sm text-muted-foreground">
        配置 JSON 搜索接口，无需编写 JavaScript。支持点分路径，例如
        data.results。网页抓取返回原始正文。
      </p>
      <label className="flex gap-2">
        <input
          type="checkbox"
          checked={active}
          onChange={(e) => {
            if (
              e.target.checked &&
              !window.confirm("启用后将用可视化配置替换搜索和抓取脚本，是否继续？")
            )
              return;
            setActive(e.target.checked);
            if (e.target.checked) {
              try {
                onChange(buildSearchScripts(config));
                setError("");
              } catch (err) {
                setError(String(err));
              }
            }
          }}
        />
        启用可视化生成（修改自动写入脚本）
      </label>
      <div className="grid gap-3 sm:grid-cols-2">
        {(
          [
            ["url", "* 搜索接口 URL"],
            ["queryKey", "* 搜索词参数名"],
            ["limitKey", "* 数量参数名"],
            ["results", "结果数组路径（空为根数组）"],
            ["title", "标题字段路径"],
            ["link", "链接字段路径"],
            ["snippet", "摘要字段路径"],
          ] as const
        )
          .filter(([key]) => key !== "limitKey" || config.sendLimit)
          .map(([key, label]) => (
            <label key={key} className="text-sm">
              {label}
              <input
                className="w-full rounded border bg-background p-2"
                required={active && ["url", "queryKey", "limitKey"].includes(key)}
                type={key === "url" ? "url" : "text"}
                value={config[key]}
                onChange={(e) => update({ ...config, [key]: e.target.value })}
              />
            </label>
          ))}
      </div>
      {(
        [
          ["post", "使用 POST JSON 请求"],
          ["auth", "使用 Bearer API Key"],
          ["sendLimit", "发送结果数量参数"],
          ["filter", "过滤无效链接"],
          ["dedupe", "按链接去重"],
          ["limit", "限制返回条数"],
          ["scrape", "启用网页正文抓取"],
        ] as const
      ).map(([key, title]) => (
        <label key={key} className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={config[key]}
            onChange={(e) => update({ ...config, [key]: e.target.checked })}
          />
          {title}
        </label>
      ))}
      {config.auth && (
        <label className="block text-sm">
          * 搜索 API Key
          <input
            type="password"
            required={active}
            autoComplete="new-password"
            className="w-full rounded border bg-background p-2"
            value={config.apiKey}
            onChange={(e) => update({ ...config, apiKey: e.target.value })}
          />
        </label>
      )}
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}（尚未写入新脚本）
        </p>
      )}
      <Button
        type="button"
        variant="outline"
        disabled={!active}
        onClick={() => {
          try {
            onChange(buildSearchScripts(config));
            setError("");
          } catch (e) {
            setError(String(e));
          }
        }}
      >
        重新生成脚本
      </Button>
      <p className="text-xs text-muted-foreground">
        下方可查看和编辑生成的脚本。已有脚本不会自动反向转换；关闭可视化生成可继续手写。
      </p>
    </section>
  );
}

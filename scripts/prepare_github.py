"""Inventory publishable source without staging, committing or contacting a remote.

Only paths and rule names are written to reports; matched credential values are
never copied into the report. Ignored device evidence is deliberately not read.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_SUFFIXES = {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".key",
                    ".hprof", ".pftrace", ".db", ".sqlite", ".sqlite3", ".log"}
PRIVATE_PARTS = {"node_modules", ".gradle", ".gradle-user", ".idea", ".kotlin",
                 "build", "captures", "__pycache__"}
SECRET_RULES = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----(?:\s|\\n|[A-Za-z0-9+/=]){80,}-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"),
    "google-api-key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "provider-secret": re.compile(r"\bsk-(?:proj-|ant-api\d\d-)?[A-Za-z0-9_-]{30,}\b"),
    "credential-url": re.compile(r"https?://[A-Za-z0-9_%+.-]+:[A-Za-z0-9_%+.-]+@(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}(?=[:/\s\"'<>]|$)"),
}
PERSONAL_PATH = re.compile(r"(?:[CD]:[/\\](?:Users|Codex|Download|ErrorLogs|Apps)[/\\]|(?:--serial|serial|序列号)\s*[=: ]\s*[A-Z0-9]{10,24})", re.I)
LINK = re.compile(r"\[[^\]\n]*\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)")


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT)


def private_reason(path: str) -> str | None:
    p = Path(path)
    if any(part in PRIVATE_PARTS for part in p.parts) or path.startswith("docs/local/"):
        return "local-cache-or-evidence"
    if p.suffix.lower() in PRIVATE_SUFFIXES:
        return "private-material-or-generated-output"
    if (p.name == ".env" or p.name.startswith(".env.")) and not p.name.endswith(".example"):
        return "environment-secrets"
    if p.name in {"local.properties", "google-services.json"}:
        return "machine-configuration"
    return None


def inspect_text(text: str, url_examples: frozenset[str] = frozenset()) -> list[dict]:
    hits = []
    for name, pattern in SECRET_RULES.items():
        for match in pattern.finditer(text):
            # Reserved example hosts are deliberately used in URL-redaction tests.
            if name == "credential-url":
                host = urlsplit(match.group()).hostname or ""
                if host.endswith((".test", ".invalid", ".example")) or host in {"example.com", "example.org", "example.net"}:
                    continue
                if match.group() in url_examples:
                    continue
            hits.append({"rule": name, "line": text.count("\n", 0, match.start()) + 1})
    return hits


def missing_doc_links(path: Path, text: str) -> list[str]:
    # Fenced examples and inline-code examples are not document navigation.
    text = re.sub(r"(?ms)^\s*(`{3,}|~{3,}).*?^\s*\1\s*$", "", text)
    text = re.sub(r"`[^`\n]+`", "", text)
    missing = []
    for match in LINK.finditer(text):
        destination = unquote(match[1].strip("<>"))
        if urlsplit(destination).scheme or destination.startswith(("#", "/")):
            continue
        target = destination.split("#", 1)[0].split("?", 1)[0]
        resolved = (path.parent / target).resolve()
        lexical = Path(os.path.abspath(path.parent / target))
        local_only = lexical.is_relative_to(ROOT) and private_reason(lexical.relative_to(ROOT).as_posix())
        if target and (not resolved.exists() or local_only):
            missing.append(destination)
    return sorted(set(missing))


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/local/github-preparation-current")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / "docs/local").resolve()):
        parser.error("Reports must remain in the ignored docs/local directory")
    output.mkdir(parents=True, exist_ok=True)
    index = Path(git("rev-parse", "--git-path", "index").decode().strip())
    if not index.is_absolute():
        index = ROOT / index
    index_before = hashlib.sha256(index.read_bytes()).hexdigest()
    candidates = sorted(set(git("ls-files", "-co", "--exclude-standard", "-z").decode().split("\0")) - {""})
    changes = set(git("diff", "--name-only", "HEAD", "-z").decode().split("\0"))
    untracked = set(git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    rows, missing = [], []
    for name in candidates:
        file = ROOT / name
        if not file.exists() or not file.is_file():
            continue
        reason = private_reason(name)
        row = {"path": name, "bytes": file.stat().st_size, "changed": name in changes or name in untracked,
               "category": "excluded" if reason else "source", "reason": reason, "findings": []}
        # Never follow a link out of the checkout when inspecting source.
        if not file.resolve().is_relative_to(ROOT.resolve()):
            row.update(category="excluded", reason="external-link")
        elif not reason:
            data = file.read_bytes()
            if b"\0" not in data[:8192]:
                try:
                    text = data.decode("utf-8-sig")
                except UnicodeDecodeError:
                    text = ""
                # These exact public-host URLs test rejection of embedded userinfo;
                # any other credential in the same test file remains reportable.
                examples = frozenset({"https://u:" + "p@github.com", "https://user:" + "password@api.github.com"}) if name == "app/src/test/java/me/rerere/rikkahub/data/github/GitHubRepositoryTest.kt" else frozenset()
                row["findings"] = inspect_text(text, examples)
                if row["findings"]:
                    row.update(category="hold", reason="credential-indicator")
                if name.endswith(".md") and (name.startswith("docs/") or name in {"README.md", "README_ZH_CN.md"}):
                    if PERSONAL_PATH.search(text):
                        row["findings"].append({"rule": "machine-specific-document", "line": 0})
                        row.update(category="hold", reason="document-needs-portable-example")
                    for link in missing_doc_links(file, text):
                        missing.append({"path": name, "target": link})
            if row["category"] == "source" and (name.startswith(("desktopApp/", "docs/assets/threejs-feasibility/")) or "ThreeSpike" in name or "three-spike" in name or name == "docs/threejs-feasibility-spike.md"):
                row.update(category="separate", reason="optional-experiment")
        if row["bytes"] >= 50 * 1024 * 1024:
            row.update(category="hold", reason="large-file-review")
        rows.append(row)
    deleted = sorted(p for p in changes if p and not (ROOT / p).exists())
    summary = {"head": git("rev-parse", "HEAD").decode().strip(),
               "branch": git("branch", "--show-current").decode().strip(),
               "categories": dict(Counter(row["category"] for row in rows)),
               "changedCategories": dict(Counter(row["category"] for row in rows if row["changed"])),
               "deleted": deleted, "missingDocLinks": missing,
               "indexUnchanged": index_before == hashlib.sha256(index.read_bytes()).hexdigest()}
    (output / "manifest.json").write_text(json.dumps({"summary": summary, "files": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    for category in ("source", "separate", "hold", "excluded"):
        (output / f"{category}.paths").write_text("".join(row["path"] + "\n" for row in rows if row["category"] == category), encoding="utf-8")
        (output / f"changed-{category}.paths").write_text("".join(row["path"] + "\n" for row in rows if row["category"] == category and row["changed"]), encoding="utf-8")
    (output / "deletions.paths").write_text("".join(path + "\n" for path in deleted), encoding="utf-8")
    modules = Counter(row["path"].split('/')[0] for row in rows if row["category"] == "source" and row["changed"])
    report = ["# GitHub 推送前整理清单", "", f"本地分支：`{summary['branch']}`；HEAD：`{summary['head']}`。",
              "本工具没有暂存、提交、推送、创建 PR/标签/Release 或发布安装包；远端状态未在本次扫描中刷新。", "",
              "## 文件范围", "", "| 类别 | 当前文件 | 新增/修改 |", "| --- | ---: | ---: |"]
    for category in ("source", "separate", "hold", "excluded"):
        report.append(f"| {category} | {summary['categories'].get(category, 0)} | {summary['changedCategories'].get(category, 0)} |")
    report += ["", "`changed-source.paths` 是本轮新增/修改的源码候选；`deletions.paths` 单独列出已跟踪删除，需与其替代文件成组审阅。",
               "`source.paths` 是完整的非忽略候选快照，包含未变文件；不应直接用整库 add 代替审阅。",
               "`separate` 为 Desktop、Three.js 原型及图像等独立实验组；本次默认候选不包含它们。", "",
               "| 源码候选模块 | 新增/修改文件 |", "| --- | ---: |"]
    report.extend(f"| {module} | {count} |" for module, count in sorted(modules.items()))
    report += ["", "## 排除与保留", "", "忽略规则已排除 node_modules、Gradle/IDE 缓存、构建目录、安装包、签名材料、环境私密配置、数据库、堆/跟踪文件、用户证据和 docs/local。",
               "本机 SDK/缓存迁移脚本及其专用测试留在本地。Room schema、Gradle wrapper、运行时 AAR/JS、第三方许可证和合成测试夹具是必需构建输入，继续保留。",
               "18 份被现行指南取代的文档及原图片在 docs/local/document-cleanup-20261119 中保留可恢复归档；索引、文字配色和构建指南已更新。",
               "公开颜色夹具由 generate_color_fixtures.py 重建，原用户图片不进入候选。", "",
               "## 核验与后续", "", f"凭据/文档待处理文件：{summary['categories'].get('hold', 0)}；失效或指向本地产物的文档链接：{len(missing)}。",
               f"Git index 保持原样：{summary['indexUnchanged']}。检查仅覆盖当前候选中的常见凭据形式，不包含忽略证据或 Git 历史审计。",
               "Daily Build 的 nightly 标签和附件发布已改为 workflow_dispatch + publish_nightly 显式开启，默认不发布。",
               "下一次真正推送前刷新 origin，处理与远端组件分支的重叠，按构建/模型协议、聊天/渲染、外观/日志、测试/文档分组审阅和提交。不要重写历史、盲目整文件覆盖或归入私人数据。"]
    (output / "review.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps({**summary, "deleted": len(deleted), "missingDocLinks": len(missing)}, ensure_ascii=False, indent=2))
    return 0 if summary["indexUnchanged"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

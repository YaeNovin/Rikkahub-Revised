import { useState } from "react";
import { MoreHorizontal } from "lucide-react";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { getAssistantDisplayName } from "~/lib/display";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { ModelList } from "~/components/input/model-list";
import { ReasoningPickerButton } from "~/components/input/reasoning-picker";
import { SearchPickerButton } from "~/components/input/search-picker";
import { McpPickerButton } from "~/components/input/mcp-picker";

/** Assistant configuration stays in the chat context, separate from global settings. */
export function AssistantSettingsMenu() {
  const { currentAssistant } = useCurrentAssistant();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        variant="ghost"
        size="icon"
        className="shrink-0"
        aria-label="助手配置"
        title="更多"
        disabled={!currentAssistant}
        onClick={() => setOpen(true)}
      >
        <MoreHorizontal className="size-5" />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85svh] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>{getAssistantDisplayName(currentAssistant?.name)}</DialogTitle>
            <DialogDescription>
              修改即时保存到手机，影响该助手及其他连接的浏览器。
            </DialogDescription>
          </DialogHeader>
          {currentAssistant ? (
            <div className="space-y-6">
              <section className="space-y-3">
                <h3 className="font-medium">模型与推理参数</h3>
                <div className="flex flex-wrap items-center gap-3">
                  <ModelList className="h-10 max-w-full gap-2 px-2 [&>span]:block" />
                  <ReasoningPickerButton className="h-10 [&>span]:block" />
                </div>
                <p className="text-sm text-muted-foreground">
                  推理选项根据模型能力显示。温度、Top P、最大输出暂需在手机端编辑；自动压缩上下文可在“设置 → 会话”中配置。
                </p>
              </section>
              <section className="space-y-3">
                <h3 className="font-medium">搜索与工具</h3>
                <div className="flex flex-wrap items-center gap-3">
                  <SearchPickerButton showLabel className="h-10" />
                  <McpPickerButton showLabel className="h-10" />
                </div>
                <p className="text-sm text-muted-foreground">
                  搜索服务选择与模型内置工具开关为共享配置；MCP 绑定属于当前助手。
                </p>
              </section>
              <section className="space-y-2">
                <h3 className="font-medium">提示词与上下文</h3>
                <p className="text-sm">
                  会话独立系统提示词：
                  {currentAssistant.allowConversationSystemPrompt ? "允许" : "关闭"}
                </p>
                <p className="text-sm">
                  会话独立注入：
                  {currentAssistant.allowConversationPromptInjection ? "允许" : "关闭"}
                </p>
                <p className="text-sm text-muted-foreground">
                  系统提示词、助手资料及高级参数暂需在手机端编辑。模式、世界书和快捷消息绑定仍可通过聊天输入区的扩展菜单调整。
                </p>
              </section>
            </div>
          ) : (
            <p role="status">当前助手不可用，请关闭弹窗后重新选择助手。</p>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

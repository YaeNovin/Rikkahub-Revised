"""Exercise real chat navigation only in the isolated appearanceqa package."""
import argparse
from pathlib import Path
import re
from android_qa import ROOT, Adb

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/outputs/mica-background-regression/navigation")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    installed = adb.run("shell", "pm", "list", "packages", "--user", "0", PACKAGE)
    if not re.search(rf"(?m)^package:{re.escape(PACKAGE)}$", installed):
        raise RuntimeError("Install the isolated appearanceqa build first; the user QA package cannot be substituted")
    adb.run("shell", "am", "start", "-W", "-S", "-n", f"{PACKAGE}/me.rerere.rikkahub.RouteActivity")

    def wait(step, resource=None, texts=()):
        return adb.wait_node(lambda n: (resource is not None and n.get("resource-id") == resource) or n.get("text") in texts,
                             args.output / f"{step}.xml", PACKAGE)

    adb.tap(wait("drawer-button", "chat_open_drawer"))
    wait("drawer-open", texts=("搜索聊天", "Search chats"))
    adb.run("shell", "input", "keyevent", "4")
    adb.tap(wait("drawer-return", "chat_open_menu"))
    adb.tap(wait("message-directory", texts=("消息目录与搜索",)))
    adb.tap(wait("directory-menu", "chat_open_menu"))
    adb.tap(wait("return-to-messages", texts=("返回对话正文",)))
    adb.tap(wait("new-conversation", "chat_new_conversation"))
    wait("chat-input", "chat_input")
    print("PASS real chat drawer, directory, new conversation and composer")


if __name__ == "__main__":
    main()

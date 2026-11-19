"""Verify fixed CustomSearchPreviewActivity captures without network requests."""
from __future__ import annotations

import argparse
from pathlib import Path
from android_qa import ROOT, parse_ui


def verify(captures: Path):
    def tree(name):
        return parse_ui((captures / f"{name}.xml").read_text(encoding="utf-8-sig"))

    def editors(name):
        return list(tree(name).iterfind(".//node[@class='android.widget.EditText']"))

    def require(condition, message):
        if not condition:
            raise ValueError(message)

    default = editors("default")
    require(len(default) >= 3, "Default editor fields missing")
    require(default[1].get("text") == "", "Base URL must start empty")
    require(default[-1].get("enabled") == "false", "API Key must start disabled")
    base = editors("base-off")
    require(len(base) >= 3 and base[1].get("enabled") == "false", "Base URL checkbox must disable its editor")
    require(base[2].get("enabled") == "true", "Full endpoint remains editable")
    require(editors("key-on")[-1].get("enabled") == "true", "API Key checkbox must enable its editor")
    parameters = tree("parameters")
    require(any(n.get("class") == "android.widget.CheckBox" and n.get("checked") == "true" and n.get("enabled") == "false"
                for n in parameters.iter("node")), "Required query checkbox must stay locked")
    for name, enabled in [("parameters", "false"), ("count-on", "true")]:
        field = tree(name).find(".//node[@class='android.widget.EditText'][@text='5']")
        require(field is not None and field.get("enabled") == enabled, f"Count editor state incorrect: {name}")
    require(any(n.get("text") == "将发送的 JSON 字段：query, max_results, allowed_x_handles, from_date, language"
                for n in tree("x-scroll-4").iter("node")), "Only selected X sample fields should be listed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--captures", type=Path, default=ROOT / "app/build/outputs/custom-search")
    args = parser.parse_args()
    verify(args.captures)
    print("PASS search editor defaults, opt-in fields, locked query and exact X fields")


if __name__ == "__main__":
    main()

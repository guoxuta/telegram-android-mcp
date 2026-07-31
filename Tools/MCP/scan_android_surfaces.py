#!/usr/bin/env python3
"""Conservative source-surface scanner for the native Telegram Android app.

The output is evidence for investigation, not a capability inventory.  It keeps
file/line anchors for UI affordances, controller methods, MTProto requests and
Android platform boundaries so a reviewer can reconcile them manually.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


JAVA_ROOT = Path("TMessagesProj/src/main/java/org/telegram")
MANIFESTS = (
    Path("TMessagesProj/src/main/AndroidManifest.xml"),
    Path("TMessagesProj/config/debug/AndroidManifest.xml"),
    Path("TMessagesProj/config/debug/AndroidManifest_SDK23.xml"),
)
CONTROLLER_FILES = {
    "AccountInstance.java",
    "ApplicationLoader.java",
    "BaseController.java",
    "ContactsController.java",
    "DownloadController.java",
    "FileLoader.java",
    "LocationController.java",
    "MediaController.java",
    "MediaDataController.java",
    "MessagesController.java",
    "MessagesStorage.java",
    "NotificationsController.java",
    "SendMessagesHelper.java",
    "SharedConfig.java",
    "UserConfig.java",
}

CLASS_RE = re.compile(
    r"\b(?:public\s+)?(?:abstract\s+|final\s+)?class\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*(?:extends\s+(?P<base>[\w.$<>]+))?"
)
PUBLIC_METHOD_RE = re.compile(
    r"^\s*public\s+(?:static\s+)?(?:synchronized\s+)?(?:final\s+)?"
    r"(?P<return>[\w.$<>?, \[\]]+)\s+(?P<name>[a-zA-Z_$][\w$]*)\s*\(",
    re.MULTILINE,
)
UI_CLASS_BASES = (
    "Activity",
    "BaseFragment",
    "BottomSheet",
    "Dialog",
    "View",
    "FrameLayout",
    "LinearLayout",
    "RecyclerView",
)
UI_ACTION_PATTERNS = {
    "click": re.compile(r"\bsetOnClickListener\s*\("),
    "long_click": re.compile(r"\bsetOnLongClickListener\s*\("),
    "menu_action": re.compile(r"\b(?:onItemClick|addItem|addSubItem)\s*\("),
    "text_submit": re.compile(r"\b(?:setOnEditorActionListener|onTextChanged)\s*\("),
    "swipe_drag": re.compile(r"\b(?:onSwiped|onMove|ItemTouchHelper|setOnItemClickListener)\b"),
    "navigation": re.compile(r"\b(?:presentFragment|startActivity|showDialog)\s*\("),
    "permission_or_picker": re.compile(
        r"\b(?:requestPermissions|requestPermissionsCompat|ACTION_(?:OPEN_DOCUMENT|GET_CONTENT|PICK)|"
        r"MediaStore\.ACTION_IMAGE_CAPTURE|BiometricPrompt|Intent\.createChooser)\b"
    ),
}
RPC_USE_RE = re.compile(r"new\s+(?:TLRPC\.)?(TL_[A-Za-z0-9_]+)\s*\(")
RPC_DECL_RE = re.compile(
    r"^\s*public\s+static\s+class\s+(?P<name>TL_[A-Za-z0-9_]+)\s+"
    r"extends\s+(?P<base>[^\s{]+)",
    re.MULTILINE,
)
ROUTE_RE = re.compile(
    r"\bnew\s+(?P<name>[A-Z][A-Za-z0-9_]*(?:Activity|Fragment|Sheet|Alert|Dialog))\s*\("
)


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def rel(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def iter_java(root: Path) -> Iterable[Path]:
    source_root = root / JAVA_ROOT
    yield from sorted(source_root.rglob("*.java"))


def compact_line(text: str, start: int, max_chars: int = 260) -> str:
    value = text[start : text.find("\n", start) if "\n" in text[start:] else len(text)]
    return re.sub(r"\s+", " ", value).strip()[:max_chars]


def scan_java(root: Path) -> dict[str, Any]:
    ui_classes: list[dict[str, Any]] = []
    controller_methods: list[dict[str, Any]] = []
    ui_actions: list[dict[str, Any]] = []
    routes: list[dict[str, Any]] = []
    rpc_uses: dict[str, list[dict[str, Any]]] = defaultdict(list)
    source_counts: Counter[str] = Counter()

    for path in iter_java(root):
        relative = rel(path, root)
        text = path.read_text(encoding="utf-8", errors="replace")
        parts = relative.split("/")
        source_counts[parts[6] if len(parts) > 6 else "other"] += 1

        for match in CLASS_RE.finditer(text):
            base = match.group("base") or ""
            if relative.startswith("TMessagesProj/src/main/java/org/telegram/ui/") or any(
                marker in base for marker in UI_CLASS_BASES
            ):
                ui_classes.append(
                    {
                        "name": match.group("name"),
                        "base": base or None,
                        "path": relative,
                        "line": line_number(text, match.start()),
                    }
                )

        if path.name in CONTROLLER_FILES or "/controller/" in relative.lower():
            for match in PUBLIC_METHOD_RE.finditer(text):
                controller_methods.append(
                    {
                        "name": match.group("name"),
                        "return_type": re.sub(r"\s+", " ", match.group("return")).strip(),
                        "path": relative,
                        "line": line_number(text, match.start()),
                        "evidence": compact_line(text, match.start()),
                    }
                )

        if relative.startswith("TMessagesProj/src/main/java/org/telegram/ui/"):
            per_file: list[dict[str, Any]] = []
            for kind, pattern in UI_ACTION_PATTERNS.items():
                for match in pattern.finditer(text):
                    per_file.append(
                        {
                            "kind": kind,
                            "path": relative,
                            "line": line_number(text, match.start()),
                            "evidence": compact_line(text, match.start()),
                        }
                    )
            # Keep a bounded sample per file while retaining aggregate counts.
            ui_actions.extend(sorted(per_file, key=lambda item: item["line"])[:40])

            for match in ROUTE_RE.finditer(text):
                routes.append(
                    {
                        "destination": match.group("name"),
                        "path": relative,
                        "line": line_number(text, match.start()),
                    }
                )

        for match in RPC_USE_RE.finditer(text):
            name = match.group(1)
            if len(rpc_uses[name]) < 25:
                rpc_uses[name].append(
                    {
                        "path": relative,
                        "line": line_number(text, match.start()),
                        "evidence": compact_line(text, match.start()),
                    }
                )

    tlarpc = root / JAVA_ROOT / "tgnet/TLRPC.java"
    rpc_declarations: list[dict[str, Any]] = []
    if tlarpc.exists():
        text = tlarpc.read_text(encoding="utf-8", errors="replace")
        matches = list(RPC_DECL_RE.finditer(text))
        for index, match in enumerate(matches):
            end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
            body = text[match.start() : end]
            # Request objects implement response deserialization; ordinary TL data objects do not.
            if "deserializeResponse(" not in body:
                continue
            name = match.group("name")
            rpc_declarations.append(
                {
                    "name": name,
                    "base": match.group("base"),
                    "path": rel(tlarpc, root),
                    "line": line_number(text, match.start()),
                    "usage_count_capped": len(rpc_uses.get(name, [])),
                    "usages": rpc_uses.get(name, []),
                }
            )

    return {
        "java_files_by_area": dict(sorted(source_counts.items())),
        "ui_classes": sorted(ui_classes, key=lambda item: (item["path"], item["line"])),
        "ui_actions": sorted(ui_actions, key=lambda item: (item["path"], item["line"], item["kind"])),
        "routes": sorted(routes, key=lambda item: (item["destination"], item["path"], item["line"])),
        "controller_methods": sorted(
            controller_methods, key=lambda item: (item["path"], item["line"], item["name"])
        ),
        "rpc_requests": sorted(rpc_declarations, key=lambda item: item["name"]),
        "rpc_used_but_not_declared_as_request": sorted(set(rpc_uses) - {item["name"] for item in rpc_declarations}),
    }


def scan_manifests(root: Path) -> dict[str, Any]:
    android_ns = "{http://schemas.android.com/apk/res/android}"
    result: dict[str, Any] = {"manifests": []}
    for relative in MANIFESTS:
        path = root / relative
        if not path.exists():
            continue
        item: dict[str, Any] = {
            "path": relative.as_posix(),
            "sha256": sha256(path),
            "permissions": [],
            "components": [],
        }
        try:
            xml_root = ET.parse(path).getroot()
            for node in xml_root:
                tag = node.tag.rsplit("}", 1)[-1]
                if tag.startswith("uses-permission"):
                    item["permissions"].append(node.attrib.get(android_ns + "name"))
                if tag == "application":
                    for component in node:
                        component_tag = component.tag.rsplit("}", 1)[-1]
                        if component_tag in {"activity", "activity-alias", "service", "receiver", "provider"}:
                            item["components"].append(
                                {
                                    "type": component_tag,
                                    "name": component.attrib.get(android_ns + "name"),
                                    "exported": component.attrib.get(android_ns + "exported"),
                                    "permission": component.attrib.get(android_ns + "permission"),
                                }
                            )
            item["permissions"] = sorted(filter(None, set(item["permissions"])))
            item["components"] = sorted(
                item["components"], key=lambda value: (value["type"], value["name"] or "")
            )
        except ET.ParseError as exc:
            item["parse_error"] = str(exc)
        result["manifests"].append(item)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.repo.resolve()
    if not (root / JAVA_ROOT).is_dir():
        parser.error(f"Telegram Java root not found below {root}")

    source = scan_java(root)
    payload = {
        "schema_version": "1.0",
        "scanner": "Tools/MCP/scan_android_surfaces.py",
        "warning": "Heuristic source evidence only; scanner hits are not capabilities or runtime proof.",
        "repo_root": str(root),
        "summary": {
            "ui_classes": len(source["ui_classes"]),
            "sampled_ui_actions": len(source["ui_actions"]),
            "routes": len(source["routes"]),
            "controller_methods": len(source["controller_methods"]),
            "rpc_requests": len(source["rpc_requests"]),
            "rpc_requests_with_source_usage": sum(
                1 for item in source["rpc_requests"] if item["usage_count_capped"] > 0
            ),
        },
        "source": source,
        "android": scan_manifests(root),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

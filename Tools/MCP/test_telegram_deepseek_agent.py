from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any


TOOLS_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIR.parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from telegram_agent_interactive import SessionStore, TerminalRenderer  # noqa: E402
from telegram_deepseek_agent import (  # noqa: E402
    ApprovalPolicy,
    EventLogger,
    GATEWAY_TOOLS,
    TelegramGateway,
    TelegramToolCatalog,
    load_system_prompt,
    redact,
)


def live_tools_from_asset() -> dict[str, dict[str, Any]]:
    path = (
        REPOSITORY_ROOT
        / "TMessagesProj"
        / "src"
        / "main"
        / "assets"
        / "mcp"
        / "telegram_mcp_tools.json"
    )
    catalog = json.loads(path.read_text(encoding="utf-8"))
    result: dict[str, dict[str, Any]] = {}
    for source in catalog["tools"]:
        name = source["name"]
        domain = name.split(".")[1]
        result[name] = {
            "name": name,
            "title": source.get("title") or "",
            "description": source["description"],
            "inputSchema": source["input_schema"],
            "annotations": {
                "readOnlyHint": source["read_only"],
                "destructiveHint": source["destructive"],
                "idempotentHint": source["idempotent"],
                "openWorldHint": source["open_world"],
            },
            "_meta": {
                "io.telegram.mcp/domain": domain,
                "io.telegram.mcp/tier": source["tier"],
                "io.telegram.mcp/preferredAlternatives": source.get(
                    "preferred_alternatives", []
                ),
            },
        }
    return result


class FakeSession:
    def __init__(self, tools: dict[str, dict[str, Any]]) -> None:
        self.tools = tools
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def call(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        copied = dict(arguments or {})
        self.calls.append((name, copied))
        return {"ok": True, "data": {"tool": name, "arguments": copied}}


class TelegramAgentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.tools = live_tools_from_asset()
        cls.catalog = TelegramToolCatalog(cls.tools)

    def test_catalog_has_all_unique_expected_tools(self) -> None:
        self.assertEqual(46, len(self.tools))
        self.assertEqual(46, len(self.catalog.descriptors))
        self.assertEqual(
            {
                "account",
                "chat",
                "contact",
                "dialog",
                "draft",
                "message",
                "peer",
                "profile",
                "session",
                "settings",
                "system",
            },
            {item.domain for item in self.catalog.descriptors.values()},
        )

    def test_gateway_names_are_stable_and_unique(self) -> None:
        names = [item["function"]["name"] for item in GATEWAY_TOOLS]
        self.assertEqual(6, len(names))
        self.assertEqual(6, len(set(names)))
        self.assertTrue(all(name.startswith("telegram_") for name in names))

    def test_settings_schema_is_boolean_and_closed(self) -> None:
        get_schema = self.tools["telegram.settings.get"]["inputSchema"]
        set_schema = self.tools["telegram.settings.set"]["inputSchema"]
        allowed = get_schema["properties"]["keys"]["items"]["enum"]
        values = set_schema["properties"]["values"]
        self.assertEqual(set(allowed), set(values["properties"]))
        self.assertFalse(values["additionalProperties"])
        self.assertTrue(
            all(item["type"] == "boolean" for item in values["properties"].values())
        )

    def test_chinese_search_finds_send_and_session_tools(self) -> None:
        send = self.catalog.search("给联系人发送消息", limit=5)
        sessions = self.catalog.search("终止其他设备登录会话", limit=5)
        self.assertIn("telegram.message.send_text", {item["name"] for item in send})
        self.assertIn("telegram.session.terminate", {item["name"] for item in sessions})

    def test_schema_marks_confirmation_but_hides_local_confirm_argument(self) -> None:
        details = self.catalog.details("telegram.message.delete")
        self.assertTrue(details["confirmationRequired"])
        self.assertNotIn("_confirm", details["required"])
        self.assertIn("_confirm", details["inputSchema"]["properties"])

    def test_gateway_injects_confirmation_after_local_approval(self) -> None:
        session = FakeSession(self.tools)
        gateway = TelegramGateway(
            session,
            self.catalog,
            ApprovalPolicy("always"),
            EventLogger(None),
            max_mcp_calls=5,
            verbose=False,
        )
        result = gateway.dispatch(
            "telegram_call",
            {
                "name": "telegram.message.delete",
                "arguments": {"peer_id": 42, "message_ids": [7]},
                "purpose": "单元测试确认注入",
            },
        )
        self.assertTrue(result["ok"])
        self.assertEqual(True, session.calls[-1][1]["_confirm"])

    def test_context_uses_only_read_tools_and_respects_limit(self) -> None:
        session = FakeSession(self.tools)
        gateway = TelegramGateway(
            session,
            self.catalog,
            ApprovalPolicy("never"),
            EventLogger(None),
            max_mcp_calls=10,
            verbose=False,
        )
        result = gateway.context(
            {"includeDialogs": True, "includeMe": True, "dialogLimit": 999}
        )
        self.assertEqual({"health", "accounts", "me", "dialogs"}, set(result))
        self.assertEqual(
            [
                "telegram.system.health",
                "telegram.account.list",
                "telegram.account.get_me",
                "telegram.dialog.list",
            ],
            [name for name, _ in session.calls],
        )
        self.assertEqual(100, session.calls[-1][1]["limit"])

    def test_secrets_are_redacted(self) -> None:
        self.assertEqual(
            {"token": "[REDACTED]", "nested": {"api_key": "[REDACTED]"}},
            redact({"token": "secret", "nested": {"api_key": "sk-secret"}}),
        )

    def test_prompt_placeholders_and_session_isolation(self) -> None:
        prompt = load_system_prompt(
            REPOSITORY_ROOT / "agent" / "prompts" / "system_zh.md",
            tool_count=46,
            model="deepseek-test",
        )
        self.assertIn("46", prompt)
        self.assertIn("deepseek-test", prompt)
        self.assertNotIn("{{TOOL_COUNT}}", prompt)
        with tempfile.TemporaryDirectory() as directory:
            store = SessionStore(Path(directory))
            first = store.create(
                model="deepseek-test", thinking="enabled", reasoning_effort="high"
            )
            second = store.create(
                model="deepseek-test", thinking="enabled", reasoning_effort="high"
            )
            self.assertNotEqual(first.session_id, second.session_id)
            self.assertTrue(hasattr(TerminalRenderer, "CYAN"))


if __name__ == "__main__":
    unittest.main(verbosity=2)

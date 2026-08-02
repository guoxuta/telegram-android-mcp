#!/usr/bin/env python3
"""Run a DeepSeek tool-calling agent against Telegram Android's MCP server.

The model receives six stable gateway tools instead of every live schema at
once. It discovers task-relevant interfaces, inspects their exact schema, and
then calls the debug app through an ADB port-forwarded, authenticated endpoint.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable, Iterable

from telegram_mcp_adb_bridge import (
    DEFAULT_ADB_HOST,
    DEFAULT_ADB_PORT,
    AdbMcpHttpBridge,
)
from telegram_mcp_stdio_proxy import (
    DEFAULT_PROTOCOL_VERSION,
    DEFAULT_TOKEN,
    DEFAULT_URL,
    McpHttpBridge,
    rpc_request,
)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="backslashreplace")


# This file lives under Tools/MCP; the repository root is therefore two
# directory levels above its containing directory (Path.parents[2]).
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROMPT_PATH = REPOSITORY_ROOT / "agent" / "prompts" / "system_zh.md"
DEFAULT_CATALOG_JSON = REPOSITORY_ROOT / "agent" / "TELEGRAM_MCP_AGENT_CATALOG.json"
DEFAULT_CATALOG_MARKDOWN = REPOSITORY_ROOT / "agent" / "TELEGRAM_MCP_AGENT_CATALOG.md"
DEFAULT_SESSION_DIR = REPOSITORY_ROOT / ".cache" / "telegram-agent" / "sessions"
DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"


class AgentError(RuntimeError):
    """A user-facing agent or transport error."""


class DeepSeekRequestError(AgentError):
    """A failed DeepSeek request that may be resumed without replaying MCP calls."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


SENSITIVE_KEYS = {
    "token",
    "accesstoken",
    "refreshtoken",
    "password",
    "currentpassword",
    "newpassword",
    "recoverycode",
    "secret",
    "authorization",
    "apikey",
    "privatekey",
    "cookie",
    "credential",
    "credentials",
    "passcode",
}


def _normalized_key(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())


def redact(value: Any, key: str = "") -> Any:
    """Remove credentials and account tokens before data reaches the model/log."""
    if _normalized_key(key) in SENSITIVE_KEYS:
        return "[REDACTED]"
    if isinstance(value, dict):
        return {str(k): redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(item, key) for item in value]
    return value


TEXT_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"(?i)\bsk-[A-Za-z0-9_-]{12,}\b"),
        "[REDACTED_API_KEY]",
    ),
    (
        re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}"),
        "Bearer [REDACTED]",
    ),
    (
        re.compile(
            r"(?i)((?:api[_ -]?key|password|token|密码|密钥)\s*"
            r"(?:是|为|[:=])\s*)[^\s,;，；]+"
        ),
        r"\1[REDACTED]",
    ),
)


def redact_text(value: str) -> str:
    """Remove common credential literals before model or session storage use."""
    result = value
    for pattern, replacement in TEXT_SECRET_PATTERNS:
        result = pattern.sub(replacement, result)
    return result


def json_text(value: Any, *, indent: int | None = 2) -> str:
    return json.dumps(value, ensure_ascii=False, indent=indent, default=str)


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def walk_dicts(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for nested in value.values():
            yield from walk_dicts(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from walk_dicts(nested)


def first_string(value: Any, *keys: str) -> str | None:
    for item in walk_dicts(value):
        for key in keys:
            candidate = item.get(key)
            if isinstance(candidate, str) and candidate:
                return candidate
    return None


class TelegramMcpSession:
    """Small stateful JSON-RPC client for the installed Android MCP server."""

    def __init__(self, bridge: McpHttpBridge) -> None:
        self.bridge = bridge
        self.request_id = 100
        self.tools: dict[str, dict[str, Any]] = {}
        self.server_info: dict[str, Any] = {}

    def _next_id(self) -> int:
        self.request_id += 1
        return self.request_id

    def initialize(self) -> None:
        status, response = self.bridge.post(
            rpc_request(
                self._next_id(),
                "initialize",
                {
                    "protocolVersion": DEFAULT_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {
                        "name": "telegram-android-deepseek-agent",
                        "version": "1.0.0",
                    },
                },
            )
        )
        if status != 200 or not response or "result" not in response:
            raise AgentError(f"MCP initialize failed ({status}): {response}")
        self.server_info = response["result"]
        self.bridge.post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.refresh_tools()

    def refresh_tools(self) -> dict[str, dict[str, Any]]:
        status, response = self.bridge.post(
            rpc_request(self._next_id(), "tools/list")
        )
        if status != 200 or not response or "result" not in response:
            raise AgentError(f"MCP tools/list failed ({status}): {response}")
        tools = response["result"].get("tools") or []
        self.tools = {
            tool["name"]: tool
            for tool in tools
            if isinstance(tool, dict) and isinstance(tool.get("name"), str)
        }
        return self.tools

    def inventory(self) -> dict[str, Any]:
        status, response = self.bridge.post(
            rpc_request(
                self._next_id(),
                "resources/read",
                {"uri": "telegram://mcp/tool-catalog"},
            )
        )
        if status != 200 or not response or "result" not in response:
            raise AgentError(f"MCP inventory read failed ({status}): {response}")
        contents = response["result"].get("contents") or []
        if not contents or not isinstance(contents[0].get("text"), str):
            raise AgentError("MCP inventory resource returned no JSON text")
        return json.loads(contents[0]["text"])

    def call(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        if name not in self.tools:
            raise AgentError(f"Unknown installed Telegram MCP tool: {name}")
        status, response = self.bridge.post(
            rpc_request(
                self._next_id(),
                "tools/call",
                {"name": name, "arguments": arguments or {}},
            )
        )
        if status != 200 or not response or "result" not in response:
            raise AgentError(f"{name} MCP protocol failure ({status}): {response}")
        structured = response["result"].get("structuredContent")
        if not isinstance(structured, dict):
            raise AgentError(f"{name} returned no structuredContent")
        return structured


DOMAIN_INFO: dict[str, tuple[str, str]] = {
    "system": ("运行状态", "MCP、账号槽位和网络状态"),
    "account": ("账号", "多账号槽位与本人资料"),
    "peer": ("目标解析", "将公开用户名或稳定内部引用解析为可操作目标"),
    "dialog": ("会话", "会话列表、归档、静音和置顶"),
    "message": ("消息", "历史、搜索、发送、编辑、转发、删除和已读状态"),
    "draft": ("草稿", "设置或清除指定会话草稿"),
    "contact": ("联系人", "联系人检索、屏蔽与解除屏蔽"),
    "chat": ("群组与频道", "创建、加入、退出及资料维护"),
    "settings": ("设置", "Agent 允许范围内的本地设置"),
    "profile": ("个人资料", "姓名、简介等本人资料维护"),
    "session": ("登录会话", "列出与终止其他已授权设备会话"),
}


WORD_TRANSLATIONS = {
    "get": "读取",
    "read": "读取",
    "list": "列出",
    "create": "创建",
    "add": "添加",
    "update": "更新",
    "set": "设置",
    "change": "修改",
    "delete": "删除",
    "remove": "移除",
    "clear": "清空",
    "resolve": "解析",
    "archive": "归档",
    "unarchive": "取消归档",
    "mute": "静音",
    "unmute": "取消静音",
    "pin": "置顶",
    "unpin": "取消置顶",
    "leave": "离开",
    "join": "加入",
    "send": "发送",
    "forward": "转发",
    "edit": "编辑",
    "mark": "标记",
    "history": "历史",
    "search": "搜索",
    "draft": "草稿",
    "dialog": "会话",
    "peer": "目标",
    "contact": "联系人",
    "channel": "频道",
    "group": "群组",
    "setting": "设置",
    "settings": "设置",
    "message": "消息",
    "chat": "群组/频道",
    "account": "账号",
    "session": "登录会话",
    "profile": "资料",
    "health": "健康检查",
    "block": "屏蔽",
    "unblock": "解除屏蔽",
}


QUERY_SYNONYMS = {
    "账号": "account user slot",
    "我": "account get_me profile",
    "联系人": "contact user phone",
    "目标": "peer resolve username stable reference id",
    "会话": "dialog chat conversation",
    "对话": "dialog chat conversation",
    "消息": "message text history",
    "搜索": "search query message contact",
    "发送": "send message text",
    "转发": "forward message",
    "编辑": "edit update message profile",
    "删除": "delete message remove",
    "已读": "mark read unread",
    "未读": "mark unread read",
    "草稿": "draft set clear",
    "归档": "archive unarchive dialog",
    "静音": "mute unmute dialog",
    "置顶": "pin unpin dialog message",
    "群组": "group chat create leave title about",
    "频道": "channel chat create join title about",
    "加入": "join public username",
    "退出": "leave chat channel group",
    "屏蔽": "block unblock contact",
    "设置": "settings get set",
    "资料": "profile name bio about",
    "设备": "session authorization terminate",
    "登录会话": "session authorization terminate",
}


PREFERRED_ALTERNATIVES: tuple[tuple[str, tuple[str, ...]], ...] = ()


@dataclass(frozen=True)
class ToolDescriptor:
    name: str
    title: str
    domain: str
    domain_label: str
    summary: str
    original_description: str
    tier: str
    risk: str
    required: tuple[str, ...]
    properties: tuple[str, ...]
    alternatives: tuple[str, ...]
    semantic: bool
    internal: bool
    input_type: str | None
    output_type: str | None
    tool: dict[str, Any]

    def compact(self) -> dict[str, Any]:
        schema = self.tool.get("inputSchema") or {}
        property_summaries: dict[str, Any] = {}
        for name, value in (schema.get("properties") or {}).items():
            if not isinstance(value, dict):
                continue
            item: dict[str, Any] = {"type": value.get("type") or "object"}
            if value.get("description"):
                item["description"] = value["description"]
            if value.get("enum"):
                item["enum"] = value["enum"]
            if value.get("const") is not None:
                item["const"] = value["const"]
            property_summaries[name] = item
        return {
            "name": self.name,
            "title": self.title,
            "domain": self.domain,
            "domainLabel": self.domain_label,
            "summary": self.summary,
            "tier": self.tier,
            "risk": self.risk,
            "required": list(self.required),
            "arguments": property_summaries,
            "preferredAlternatives": list(self.alternatives),
            "inputType": self.input_type,
            "outputType": self.output_type,
        }

    def search_compact(self) -> dict[str, Any]:
        """Return only what the model needs to choose a tool.

        Full argument descriptions and enum values remain available through
        telegram_tool_schema.  Keeping them out of search results materially
        reduces DeepSeek context usage when a query matches several tools.
        """
        return {
            "name": self.name,
            "title": self.title,
            "domain": self.domain,
            "summary": self.summary,
            "tier": self.tier,
            "risk": self.risk,
            "required": list(self.required),
            "argumentNames": list(self.properties),
            "preferredAlternatives": list(self.alternatives),
        }


class TelegramToolCatalog:
    """Enrich and search the live MCP tool list for an LLM agent."""

    def __init__(self, tools: dict[str, dict[str, Any]]) -> None:
        self.tools = tools
        self.descriptors = {
            name: self._describe(tool) for name, tool in tools.items()
        }

    @staticmethod
    def _domain(tool: dict[str, Any]) -> str:
        meta = tool.get("_meta") or {}
        value = meta.get("io.telegram.mcp/domain")
        if isinstance(value, str) and value:
            return value
        parts = str(tool.get("name", "")).split(".")
        return parts[1] if len(parts) > 2 else "unknown"

    @staticmethod
    def _translated_action(title: str) -> str:
        action = title.split(":", 1)[-1].strip()
        words = re.findall(r"[A-Za-z0-9]+", action)
        translated = [WORD_TRANSLATIONS.get(word.lower(), word) for word in words]
        return "".join(translated) if translated else action

    @staticmethod
    def _alternatives(name: str) -> tuple[str, ...]:
        for raw_name, alternatives in PREFERRED_ALTERNATIVES:
            if name == raw_name:
                return alternatives
        return ()

    def _describe(self, tool: dict[str, Any]) -> ToolDescriptor:
        name = str(tool.get("name") or "")
        title = str(tool.get("title") or name)
        description = str(tool.get("description") or "")
        domain = self._domain(tool)
        domain_label, domain_description = DOMAIN_INFO.get(
            domain, (domain, f"{domain} 功能")
        )
        annotations = tool.get("annotations") or {}
        meta = tool.get("_meta") or {}
        schema = tool.get("inputSchema") or {}
        generic = not description
        semantic = True
        internal = bool(meta.get("io.telegram.mcp/internal"))
        destructive = bool(annotations.get("destructiveHint")) or "_confirm" in (
            schema.get("properties") or {}
        )
        read_only = bool(annotations.get("readOnlyHint"))
        open_world = bool(annotations.get("openWorldHint"))
        if destructive:
            risk = "destructive/confirmation-required"
        elif open_world:
            risk = "external-or-network"
        elif read_only:
            risk = "read-only"
        else:
            risk = "write"
        declared_tier = str(meta.get("io.telegram.mcp/tier") or "")
        if declared_tier in {"preferred", "standard", "advanced", "internal"}:
            tier = declared_tier
        elif internal:
            tier = "internal"
        elif semantic:
            tier = "preferred"
        elif read_only:
            tier = "standard"
        else:
            tier = "advanced"
        required = tuple(
            str(value)
            for value in (schema.get("required") or [])
            if value != "_confirm"
        )
        properties = tuple(
            str(value)
            for value in (schema.get("properties") or {})
            if value not in {"_confirm", "_timeoutMs"}
        )
        action = self._translated_action(title)
        if generic:
            summary = f"{domain_label}：{action}。直接执行 Telegram 的“{title}”底层原子操作。"
        else:
            summary = f"{domain_label}：{description.strip()}"
        if required:
            summary += f" 必填参数：{', '.join(required)}。"
        elif properties:
            summary += f" 可选参数：{', '.join(properties)}。"
        else:
            summary += " 无业务参数。"
        declared_alternatives = meta.get("io.telegram.mcp/preferredAlternatives")
        alternatives = (
            tuple(str(value) for value in declared_alternatives)
            if isinstance(declared_alternatives, list)
            else self._alternatives(name)
        )
        if alternatives:
            summary += f" Agent 通常优先使用：{', '.join(alternatives)}。"
        if tier == "advanced":
            summary += f" 属于{domain_description}的底层接口，仅在语义接口不能满足时使用。"
        return ToolDescriptor(
            name=name,
            title=title,
            domain=domain,
            domain_label=domain_label,
            summary=summary,
            original_description=description,
            tier=tier,
            risk=risk,
            required=required,
            properties=properties,
            alternatives=alternatives,
            semantic=semantic,
            internal=internal,
            input_type=meta.get("io.telegram.mcp/inputType"),
            output_type=meta.get("io.telegram.mcp/outputType"),
            tool=tool,
        )

    def capabilities(self, domain: str | None = None) -> dict[str, Any]:
        descriptors = list(self.descriptors.values())
        if domain:
            descriptors = [item for item in descriptors if item.domain == domain]
        by_domain: dict[str, dict[str, Any]] = {}
        grouped: dict[str, list[ToolDescriptor]] = defaultdict(list)
        for descriptor in descriptors:
            grouped[descriptor.domain].append(descriptor)
        for key, items in sorted(grouped.items()):
            label, purpose = DOMAIN_INFO.get(key, (key, key))
            by_domain[key] = {
                "label": label,
                "purpose": purpose,
                "tools": len(items),
                "preferred": sum(item.tier == "preferred" for item in items),
                "readOnly": sum(item.risk == "read-only" for item in items),
                "confirmationRequired": sum(
                    item.risk == "destructive/confirmation-required" for item in items
                ),
            }
        return {
            "total": len(descriptors),
            "domains": by_domain,
            "workflow": [
                "先调用 telegram_get_context 获取账号状态和真实会话摘要。",
                "用 telegram_search_tools 搜索任务能力，不要凭记忆猜接口。",
                "用 telegram_tool_schema 核对参数、枚举、幂等键和确认要求。",
                "写操作先解析 peer，再调用 telegram_call，并用读取接口回读验证。",
            ],
        }

    @staticmethod
    def _expanded_query(query: str) -> str:
        expanded = [query]
        for chinese, english in QUERY_SYNONYMS.items():
            if chinese in query:
                expanded.append(english)
        return " ".join(expanded).lower()

    @staticmethod
    def _tokens(value: str) -> set[str]:
        return {
            token
            for token in re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]+", value.lower())
            if len(token) > 1
        }

    def search(
        self,
        query: str,
        *,
        domain: str | None = None,
        tier: str | None = None,
        limit: int = 12,
    ) -> list[dict[str, Any]]:
        expanded = self._expanded_query(query)
        query_tokens = self._tokens(expanded)
        scored: list[tuple[float, ToolDescriptor]] = []
        for descriptor in self.descriptors.values():
            if domain and descriptor.domain != domain:
                continue
            if tier and descriptor.tier != tier:
                continue
            haystack = " ".join(
                (
                    descriptor.name,
                    descriptor.title,
                    descriptor.summary,
                    descriptor.domain,
                    " ".join(descriptor.required),
                    " ".join(descriptor.properties),
                )
            ).lower()
            haystack_tokens = self._tokens(haystack)
            score = float(len(query_tokens & haystack_tokens) * 10)
            for token in query_tokens:
                if token in descriptor.name.lower():
                    score += 8
                elif token in haystack:
                    score += 3
            if expanded.strip() in haystack:
                score += 20
            if descriptor.tier == "preferred":
                score += 5
            elif descriptor.tier == "internal":
                score -= 5
            if score > 0 or not query.strip():
                scored.append((score, descriptor))
        scored.sort(key=lambda item: (-item[0], item[1].name))
        return [
            descriptor.search_compact()
            for _, descriptor in scored[: max(1, min(limit, 30))]
        ]

    def details(self, name: str) -> dict[str, Any]:
        descriptor = self.descriptors.get(name)
        if descriptor is None:
            suggestions = self.search(name, limit=5)
            raise AgentError(
                f"Unknown tool {name!r}. Suggestions: "
                + ", ".join(item["name"] for item in suggestions)
            )
        result = descriptor.compact()
        result.update(
            {
                "description": descriptor.original_description,
                "inputSchema": descriptor.tool.get("inputSchema") or {},
                "outputSchema": descriptor.tool.get("outputSchema"),
                "annotations": descriptor.tool.get("annotations") or {},
                "confirmationRequired": descriptor.risk
                == "destructive/confirmation-required",
                "secretArguments": [
                    key
                    for key in descriptor.properties
                    if _normalized_key(key) in SENSITIVE_KEYS
                ],
                "secretReferenceSyntax": "Use ${ENV:VARIABLE_NAME} so the model never receives the actual secret.",
            }
        )
        return result

    def export_json(self) -> dict[str, Any]:
        return {
            "generatedAt": now_iso(),
            "tools": [
                self.descriptors[name].compact() for name in sorted(self.descriptors)
            ],
            "summary": self.capabilities(),
        }

    def export_markdown(self) -> str:
        lines = [
            "# Telegram Android MCP Agent 接口目录",
            "",
            f"生成时间：`{now_iso()}`",
            "",
            f"共 `{len(self.descriptors)}` 个已安装接口。Agent 必须先解析目标、核对 schema，并在写入后回读验证。",
            "",
            "风险：`read-only` 只读；`write` 写入；`external-or-network` 依赖外部服务；`destructive/confirmation-required` 必须由人确认。",
            "",
        ]
        grouped: dict[str, list[ToolDescriptor]] = defaultdict(list)
        for descriptor in self.descriptors.values():
            grouped[descriptor.domain].append(descriptor)
        for domain, descriptors in sorted(grouped.items()):
            label, purpose = DOMAIN_INFO.get(domain, (domain, domain))
            lines.extend(
                [
                    f"## {label}（`{domain}`，{len(descriptors)} 个）",
                    "",
                    purpose + "。",
                    "",
                    "| MCP 接口 | 层级 | 风险 | 功能与调用提示 |",
                    "| --- | --- | --- | --- |",
                ]
            )
            for descriptor in sorted(descriptors, key=lambda item: item.name):
                summary = descriptor.summary.replace("|", "\\|").replace("\n", " ")
                lines.append(
                    f"| `{descriptor.name}` | {descriptor.tier} | {descriptor.risk} | {summary} |"
                )
            lines.append("")
        return "\n".join(lines).rstrip()


SECRET_REFERENCE = re.compile(r"^\$\{ENV:([A-Za-z_][A-Za-z0-9_]*)\}$")


def resolve_secret_references(value: Any) -> Any:
    """Resolve ${ENV:NAME} placeholders immediately before the local MCP call."""
    if isinstance(value, str):
        match = SECRET_REFERENCE.fullmatch(value.strip())
        if not match:
            return value
        variable = match.group(1)
        secret = os.environ.get(variable)
        if secret is None:
            raise AgentError(f"Required secret environment variable is not set: {variable}")
        return secret
    if isinstance(value, dict):
        return {str(key): resolve_secret_references(item) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve_secret_references(item) for item in value]
    return value


class ApprovalPolicy:
    def __init__(self, mode: str) -> None:
        self.mode = mode

    def authorize(
        self,
        descriptor: ToolDescriptor,
        arguments: dict[str, Any],
        purpose: str,
    ) -> tuple[bool, str]:
        requires_confirmation = descriptor.risk == "destructive/confirmation-required"
        if not requires_confirmation:
            return True, "not-required"
        if self.mode == "always":
            return True, "auto-approved"
        if self.mode == "never":
            return False, "approval mode is never"
        if not sys.stdin.isatty():
            return False, "interactive confirmation is unavailable"
        print("\n需要人工确认高影响 MCP 调用：", file=sys.stderr)
        print(f"  接口：{descriptor.name}", file=sys.stderr)
        print(f"  功能：{descriptor.summary}", file=sys.stderr)
        print(f"  目的：{purpose or '(Agent 未说明)'}", file=sys.stderr)
        print(f"  参数：{json_text(redact(arguments), indent=None)}", file=sys.stderr)
        answer = input("允许执行？输入 yes 继续：").strip().lower()
        return answer == "yes", "human-approved" if answer == "yes" else "human-rejected"


EventListener = Callable[[str, dict[str, Any]], None]


class EventLogger:
    def __init__(
        self,
        path: Path | None,
        listeners: Iterable[EventListener] | None = None,
    ) -> None:
        self.path = path
        self.listeners = list(listeners or [])

    @property
    def has_listeners(self) -> bool:
        return bool(self.listeners)

    def subscribe(self, listener: EventListener) -> None:
        self.listeners.append(listener)

    def write(self, event: str, data: dict[str, Any]) -> None:
        safe_data = redact(data)
        for listener in tuple(self.listeners):
            try:
                listener(event, safe_data)
            except Exception as error:
                print(f"Agent event listener failed: {error}", file=sys.stderr)
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            trace_data = safe_data
            if event in {"reasoning", "assistant_final"} and isinstance(
                safe_data.get("content"), str
            ):
                # The interactive terminal may display model text, while the
                # opt-in trace remains metadata-only for privacy.
                trace_data = {
                    key: value
                    for key, value in safe_data.items()
                    if key != "content"
                }
                trace_data["length"] = len(safe_data["content"])
            record = {"at": now_iso(), "event": event, "data": trace_data}
            with self.path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")


GATEWAY_TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "telegram_capabilities",
            "description": "查看 Telegram Android MCP 的功能域、接口数量、风险统计和推荐调用流程。开始复杂任务时使用；不要凭记忆猜接口。",
            "parameters": {
                "type": "object",
                "properties": {
                    "domain": {
                        "type": "string",
                        "description": "可选的精确 domain，如 dialog、message、contact、chat、settings、session。",
                    }
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "telegram_search_tools",
            "description": "按自然语言检索当前安装的 Telegram MCP。返回功能、风险和必填参数。写操作前先检索；不得猜测工具名。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "功能关键词，可用中文或英文，例如：发送消息、归档会话、创建群组、终止其他设备会话。",
                    },
                    "domain": {"type": "string"},
                    "tier": {
                        "type": "string",
                        "enum": ["preferred", "standard", "advanced", "internal"],
                    },
                    "limit": {"type": "integer", "minimum": 1, "maximum": 30, "default": 12},
                },
                "required": ["query"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "telegram_tool_schema",
            "description": "读取一个真实 MCP 的完整 JSON Schema、枚举、风险、秘密参数和替代接口。调用 telegram_call 前必须用它核对不熟悉的参数。",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "完整 MCP 名称，例如 telegram.message.send_text。",
                    }
                },
                "required": ["name"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "telegram_call",
            "description": "调用真实 Telegram MCP。必须使用搜索/schema 返回的精确名称和参数；不要猜 peer_id/message_id。高影响操作由本地框架二次确认并注入 _confirm。",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "arguments": {
                        "type": "object",
                        "description": "完全符合目标 MCP JSON Schema 的参数。秘密用 ${ENV:变量名} 引用。",
                        "additionalProperties": True,
                    },
                    "purpose": {
                        "type": "string",
                        "description": "用一句话说明为什么调用，供人工确认和审计。",
                    },
                },
                "required": ["name", "arguments", "purpose"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "telegram_batch",
            "description": "按顺序执行多个已经核对 schema 的真实 MCP，适合独立读取或确定性的多步写入。含高影响操作时仍会逐项人工确认。",
            "parameters": {
                "type": "object",
                "properties": {
                    "calls": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 20,
                        "items": {
                            "type": "object",
                            "properties": {
                                "name": {"type": "string"},
                                "arguments": {"type": "object", "additionalProperties": True},
                                "purpose": {"type": "string"},
                            },
                            "required": ["name", "arguments", "purpose"],
                            "additionalProperties": False,
                        },
                    },
                    "stopOnError": {"type": "boolean", "default": True},
                },
                "required": ["calls"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "telegram_get_context",
            "description": "读取 MCP 健康状态、账号摘要、本人资料和最近会话，获得真实 account/peer_id。涉及现有会话或联系人时应先调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "includeDialogs": {"type": "boolean", "default": True},
                    "includeMe": {"type": "boolean", "default": True},
                    "dialogLimit": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
                },
                "additionalProperties": False,
            },
        },
    },
]


class TelegramGateway:
    def __init__(
        self,
        session: TelegramMcpSession,
        catalog: TelegramToolCatalog,
        approvals: ApprovalPolicy,
        logger: EventLogger,
        *,
        max_mcp_calls: int,
        verbose: bool,
    ) -> None:
        self.session = session
        self.catalog = catalog
        self.approvals = approvals
        self.logger = logger
        self.max_mcp_calls = max_mcp_calls
        self.verbose = verbose
        self.mcp_calls = 0

    def reset_budget(self) -> None:
        """Apply the MCP call budget independently to every user turn."""
        self.mcp_calls = 0

    def _check_budget(self) -> None:
        if self.mcp_calls >= self.max_mcp_calls:
            raise AgentError(
                f"MCP call budget exhausted ({self.max_mcp_calls}); stop and summarize progress"
            )

    def _call_real_tool(
        self, name: str, arguments: dict[str, Any], purpose: str
    ) -> dict[str, Any]:
        self._check_budget()
        descriptor = self.catalog.descriptors.get(name)
        if descriptor is None:
            raise AgentError(f"Unknown installed MCP tool: {name}")
        approved, reason = self.approvals.authorize(descriptor, arguments, purpose)
        if not approved:
            result = {
                "ok": False,
                "error": {
                    "code": "human_approval_required",
                    "message": f"MCP call was not approved: {reason}",
                },
                "tool": name,
            }
            self.logger.write("mcp_rejected", {"tool": name, "arguments": arguments, "purpose": purpose})
            return result
        resolved = resolve_secret_references(arguments)
        schema_properties = (
            descriptor.tool.get("inputSchema", {}).get("properties", {})
        )
        if "_confirm" in schema_properties:
            resolved["_confirm"] = True
        self.mcp_calls += 1
        if self.verbose and not self.logger.has_listeners:
            print(
                f"[MCP {self.mcp_calls}/{self.max_mcp_calls}] {name} — {purpose}",
                file=sys.stderr,
            )
        self.logger.write(
            "mcp_call",
            {
                "tool": name,
                "arguments": arguments,
                "purpose": purpose,
                "approval": reason,
                "number": self.mcp_calls,
                "budget": self.max_mcp_calls,
                "risk": descriptor.risk,
            },
        )
        result = self.session.call(name, resolved)
        safe_result = redact(result)
        self.logger.write("mcp_result", {"tool": name, "result": safe_result})
        return safe_result

    def _safe_context_call(self, name: str, arguments: dict[str, Any] | None = None) -> Any:
        try:
            return self._call_real_tool(name, arguments or {}, "读取当前 Telegram 安全上下文")
        except Exception as error:
            return {"ok": False, "error": str(error), "tool": name}

    def context(self, arguments: dict[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = {
            "health": self._safe_context_call("telegram.system.health"),
            "accounts": self._safe_context_call("telegram.account.list"),
        }
        if arguments.get("includeMe", True):
            result["me"] = self._safe_context_call("telegram.account.get_me")
        if arguments.get("includeDialogs", True):
            limit = max(1, min(int(arguments.get("dialogLimit", 20)), 100))
            result["dialogs"] = self._safe_context_call(
                "telegram.dialog.list", {"limit": limit}
            )
        return redact(result)

    def dispatch(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        self.logger.write("gateway_call", {"name": name, "arguments": arguments})
        try:
            if name == "telegram_capabilities":
                result = self.catalog.capabilities(arguments.get("domain"))
            elif name == "telegram_search_tools":
                result = {
                    "query": arguments.get("query", ""),
                    "results": self.catalog.search(
                        str(arguments.get("query", "")),
                        domain=arguments.get("domain"),
                        tier=arguments.get("tier"),
                        limit=int(arguments.get("limit", 12)),
                    ),
                }
            elif name == "telegram_tool_schema":
                result = self.catalog.details(str(arguments.get("name", "")))
            elif name == "telegram_call":
                raw_arguments = arguments.get("arguments") or {}
                if not isinstance(raw_arguments, dict):
                    raise AgentError("telegram_call.arguments must be an object")
                result = self._call_real_tool(
                    str(arguments.get("name", "")),
                    raw_arguments,
                    str(arguments.get("purpose", "")),
                )
            elif name == "telegram_batch":
                calls = arguments.get("calls") or []
                if not isinstance(calls, list):
                    raise AgentError("telegram_batch.calls must be an array")
                results = []
                failed_index: int | None = None
                for index, call in enumerate(calls):
                    gateway_operation_id = f"batch-{uuid.uuid4().hex}"
                    try:
                        if not isinstance(call, dict):
                            item = {
                                "ok": False,
                                "error": {
                                    "code": "invalid_batch_item",
                                    "message": "batch item must be an object",
                                    "retryable": False,
                                },
                            }
                        else:
                            raw_item_arguments = call.get("arguments") or {}
                            if not isinstance(raw_item_arguments, dict):
                                raise AgentError("batch item arguments must be an object")
                            item = self._call_real_tool(
                                str(call.get("name", "")),
                                raw_item_arguments,
                                str(call.get("purpose", "")),
                            )
                    except Exception as error:  # Preserve all earlier effects and results.
                        item = {
                            "ok": False,
                            "error": {
                                "code": "batch_transport_or_gateway_error",
                                "message": str(error),
                                "retryable": False,
                                "details": {
                                    "outcome": "unknown",
                                    "read_before_retry": True,
                                },
                            },
                        }
                    data = item.get("data") if isinstance(item, dict) else None
                    error_data = item.get("error") if isinstance(item, dict) else None
                    operation_id = (
                        data.get("operation_id")
                        if isinstance(data, dict)
                        else None
                    ) or (
                        (error_data.get("details") or {}).get("operation_id")
                        if isinstance(error_data, dict)
                        and isinstance(error_data.get("details"), dict)
                        else None
                    ) or gateway_operation_id
                    results.append(
                        {
                            "index": index,
                            "operation_id": operation_id,
                            "result": item,
                        }
                    )
                    if arguments.get("stopOnError", True) and not item.get("ok", False):
                        failed_index = index
                        break
                if failed_index is None:
                    failed_index = next(
                        (
                            entry["index"]
                            for entry in results
                            if not entry["result"].get("ok", False)
                        ),
                        None,
                    )
                result = {
                    "ok": failed_index is None and len(results) == len(calls),
                    "results": results,
                    "completed_count": len(results),
                    "succeeded_count": sum(
                        bool(entry["result"].get("ok", False)) for entry in results
                    ),
                    "failed_index": failed_index,
                    "stopped_early": len(results) < len(calls),
                    "safe_to_replay_entire_batch": False if results else True,
                    "replay_guidance": (
                        "Inspect each result and operation_id; independently read back any failed or unknown write before retrying only that item."
                    ),
                }
            elif name == "telegram_get_context":
                result = self.context(arguments)
            else:
                raise AgentError(f"Unknown gateway tool: {name}")
            return redact(result)
        except Exception as error:
            failure = {
                "ok": False,
                "error": {"code": "gateway_error", "message": str(error)},
                "gatewayTool": name,
            }
            self.logger.write("gateway_error", failure)
            return failure


class DeepSeekClient:
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        timeout_seconds: int,
        retries: int,
        retry_listener: Callable[[dict[str, Any]], None] | None = None,
    ) -> None:
        if not api_key:
            raise AgentError("DEEPSEEK_API_KEY is not set")
        stripped_api_key = api_key.strip()
        self.api_key_shape = {
            "length": len(stripped_api_key),
            "startsWithSk": stripped_api_key.startswith("sk-"),
            "charactersValid": bool(
                re.fullmatch(r"sk-[A-Za-z0-9_-]+", stripped_api_key)
            ),
            "whitespaceTrimmed": stripped_api_key != api_key,
        }
        self.api_key = stripped_api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.retries = max(0, retries)
        self.retry_listener = retry_listener
        self.opener = urllib.request.build_opener(
            # Respect the machine's configured HTTPS proxy when one is needed
            # to reach DeepSeek.  An empty mapping would silently disable it.
            urllib.request.ProxyHandler(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )

    def _wait_before_retry(
        self,
        *,
        operation: str,
        attempt: int,
        error: Exception,
    ) -> None:
        """Report and back off before retrying only the DeepSeek HTTP request."""
        delay_seconds = min(8, 2**attempt)
        if self.retry_listener is not None:
            self.retry_listener(
                {
                    "operation": operation,
                    "failedAttempt": attempt + 1,
                    "nextAttempt": attempt + 2,
                    "maxAttempts": self.retries + 1,
                    "delaySeconds": delay_seconds,
                    "errorType": type(error).__name__,
                    "error": redact_text(str(error))[:500],
                }
            )
        time.sleep(delay_seconds)

    @property
    def endpoint(self) -> str:
        if self.base_url.endswith("/chat/completions"):
            return self.base_url
        return self.base_url + "/chat/completions"

    @property
    def models_endpoint(self) -> str:
        suffix = "/chat/completions"
        root = self.base_url[: -len(suffix)] if self.base_url.endswith(suffix) else self.base_url
        return root.rstrip("/") + "/models"

    @staticmethod
    def _http_error(error: urllib.error.HTTPError, summary: dict[str, Any]) -> AgentError:
        raw = error.read().decode("utf-8", "replace")
        headers = error.headers or {}
        request_id = (
            headers.get("x-request-id")
            or headers.get("x-ds-request-id")
            or headers.get("x-ds-trace-id")
            or headers.get("cf-ray")
            or "unavailable"
        )
        response_body = raw[:2000].strip() or "<empty response body>"
        return AgentError(
            f"DeepSeek HTTP {error.code} {error.reason}; "
            f"request_id={request_id}; response={response_body}; "
            f"request_summary={json_text(summary, indent=None)}"
        )

    def list_models(self) -> list[str]:
        """Validate authentication using GET /models without inference."""
        request = urllib.request.Request(
            self.models_endpoint,
            method="GET",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Accept": "application/json",
                "User-Agent": "telegram-deepseek-agent/1.0",
            },
        )
        summary = {
            "method": "GET",
            "endpoint": "/models",
            "apiKeyShape": self.api_key_shape,
        }
        last_error: Exception | None = None
        attempts_made = 0
        for attempt in range(self.retries + 1):
            attempts_made = attempt + 1
            try:
                with self.opener.open(request, timeout=self.timeout_seconds) as response:
                    raw = response.read()
                data = json.loads(raw.decode("utf-8"))
                models = data.get("data") or []
                return [
                    str(item.get("id"))
                    for item in models
                    if isinstance(item, dict) and item.get("id")
                ]
            except urllib.error.HTTPError as error:
                last_error = self._http_error(error, summary)
                if error.code not in {408, 409, 429, 500, 502, 503, 504}:
                    break
            except (
                urllib.error.URLError,
                TimeoutError,
                ssl.SSLError,
                ConnectionError,
                json.JSONDecodeError,
            ) as error:
                last_error = error
            if attempt < self.retries:
                self._wait_before_retry(
                    operation="GET /models",
                    attempt=attempt,
                    error=last_error or AgentError("unknown error"),
                )
        raise AgentError(
            f"DeepSeek model-list request failed after {attempts_made} attempt(s): "
            f"{last_error}"
        )

    def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 0.1,
        max_tokens: int | None = None,
        thinking: str = "enabled",
        reasoning_effort: str = "high",
    ) -> dict[str, Any]:
        compatible_messages: list[dict[str, Any]] = []
        for original in messages:
            message = dict(original)
            if message.get("role") == "assistant" and message.get("tool_calls"):
                # DeepSeek V4 requires a non-null assistant content field on
                # tool-call turns, even when there is no user-facing text.
                if message.get("content") is None:
                    message["content"] = ""
            if thinking == "disabled":
                # reasoning_content belongs to thinking-mode tool history and
                # may be rejected by non-thinking compatibility paths.
                message.pop("reasoning_content", None)
            compatible_messages.append(message)
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": compatible_messages,
            "stream": False,
        }
        payload["thinking"] = {"type": thinking}
        if thinking == "enabled":
            payload["reasoning_effort"] = reasoning_effort
        else:
            payload["temperature"] = temperature
        if tools:
            payload["tools"] = tools
            # Do not send tool_choice in DeepSeek V4 thinking mode.  DeepSeek
            # defaults to auto when tools are present and explicitly rejects
            # this OpenAI compatibility field in thinking+tools requests.
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
                "User-Agent": "telegram-deepseek-agent/1.0",
            },
        )
        last_error: Exception | None = None
        last_retryable = True
        attempts_made = 0
        for attempt in range(self.retries + 1):
            attempts_made = attempt + 1
            try:
                with self.opener.open(request, timeout=self.timeout_seconds) as response:
                    raw = response.read()
                data = json.loads(raw.decode("utf-8"))
                choices = data.get("choices") or []
                if not choices or not isinstance(choices[0].get("message"), dict):
                    raise AgentError(f"DeepSeek returned no assistant message: {redact(data)}")
                return choices[0]["message"]
            except urllib.error.HTTPError as error:
                request_summary: dict[str, Any] = {
                    "model": self.model,
                    "thinking": thinking,
                    "reasoningEffort": reasoning_effort
                    if thinking == "enabled"
                    else None,
                    "messages": len(compatible_messages),
                    "tools": len(tools or []),
                    "toolChoiceSent": False,
                    "apiKeyShape": self.api_key_shape,
                }
                last_error = self._http_error(error, request_summary)
                last_retryable = error.code in {408, 409, 429, 500, 502, 503, 504}
                if not last_retryable:
                    break
            except (
                urllib.error.URLError,
                TimeoutError,
                ssl.SSLError,
                ConnectionError,
                json.JSONDecodeError,
            ) as error:
                last_error = error
                last_retryable = True
            if attempt < self.retries:
                self._wait_before_retry(
                    operation="POST /chat/completions",
                    attempt=attempt,
                    error=last_error or AgentError("unknown error"),
                )
        raise DeepSeekRequestError(
            f"DeepSeek request failed after {attempts_made} attempt(s): {last_error}",
            retryable=last_retryable,
        )


def load_system_prompt(path: Path, *, tool_count: int, model: str) -> str:
    if not path.is_file():
        raise AgentError(f"System prompt not found: {path}")
    template = path.read_text(encoding="utf-8")
    return template.replace("{{TOOL_COUNT}}", str(tool_count)).replace(
        "{{MODEL}}", model
    )


class DeepSeekTelegramAgent:
    def __init__(
        self,
        client: DeepSeekClient,
        gateway: TelegramGateway,
        system_prompt: str,
        *,
        max_rounds: int,
        temperature: float,
        thinking: str,
        reasoning_effort: str,
        verbose: bool,
        logger: EventLogger,
        messages: list[dict[str, Any]] | None = None,
        on_messages_changed: Callable[[list[dict[str, Any]]], None] | None = None,
    ) -> None:
        self.client = client
        self.gateway = gateway
        self.system_prompt = system_prompt
        self.max_rounds = max_rounds
        self.temperature = temperature
        self.thinking = thinking
        self.reasoning_effort = reasoning_effort
        self.verbose = verbose
        self.logger = logger
        self.on_messages_changed = on_messages_changed
        self.messages = list(messages or [])
        if not self.messages:
            self.messages = [{"role": "system", "content": system_prompt}]
        elif self.messages[0].get("role") == "system":
            # Policies and the live tool count may have changed since a saved
            # session was created, so refresh only the system message.
            self.messages[0] = {"role": "system", "content": system_prompt}
        else:
            self.messages.insert(0, {"role": "system", "content": system_prompt})

    def _messages_changed(self) -> None:
        if self.on_messages_changed is not None:
            self.on_messages_changed(self.messages)

    def _append_message(self, message: dict[str, Any]) -> None:
        self.messages.append(message)
        self._messages_changed()

    def reset(self) -> None:
        self.messages = [{"role": "system", "content": self.system_prompt}]
        self._messages_changed()

    def abort_pending_turn(self, reason: str) -> None:
        """Close an interrupted tool turn so a saved session remains resumable."""
        if not self.messages or self.messages[-1].get("role") == "system":
            return
        existing_tool_ids = {
            str(message.get("tool_call_id"))
            for message in self.messages
            if message.get("role") == "tool" and message.get("tool_call_id")
        }
        last_user_index = max(
            (
                index
                for index, message in enumerate(self.messages)
                if message.get("role") == "user"
            ),
            default=0,
        )
        pending_calls: list[dict[str, Any]] = []
        for message in self.messages[last_user_index + 1 :]:
            if message.get("role") == "assistant" and message.get("tool_calls"):
                pending_calls.extend(message.get("tool_calls") or [])
        for tool_call in pending_calls:
            call_id = str(tool_call.get("id") or "")
            if not call_id or call_id in existing_tool_ids:
                continue
            self._append_message(
                {
                    "role": "tool",
                    "tool_call_id": call_id,
                    "content": json.dumps(
                        {
                            "ok": False,
                            "error": {
                                "code": "turn_interrupted",
                                "message": "该工具调用在完成前被中断。",
                            },
                        },
                        ensure_ascii=False,
                    ),
                }
            )
        last = self.messages[-1]
        if last.get("role") != "assistant" or last.get("tool_calls"):
            safe_reason = redact_text(reason).strip()[:500]
            self._append_message(
                {
                    "role": "assistant",
                    "content": f"[本地运行状态] 上一轮未完成：{safe_reason}",
                }
            )
        self.logger.write("turn_aborted", {"reason": redact_text(reason)[:500]})

    @staticmethod
    def _assistant_message(raw: dict[str, Any]) -> dict[str, Any]:
        tool_calls = raw.get("tool_calls") or []
        content = raw.get("content")
        if tool_calls and content is None:
            content = ""
        message: dict[str, Any] = {
            "role": "assistant",
            "content": content,
        }
        if tool_calls:
            message["tool_calls"] = tool_calls
        if raw.get("reasoning_content") is not None:
            message["reasoning_content"] = raw["reasoning_content"]
        return message

    def run(self, user_text: str) -> str:
        safe_user_text = redact_text(user_text)
        if safe_user_text != user_text:
            self.logger.write(
                "secret_redacted",
                {"message": "检测到疑似秘密，已在发送给模型和保存会话前脱敏。"},
            )
        self.gateway.reset_budget()
        self._append_message({"role": "user", "content": safe_user_text})
        self.logger.write("user_turn", {"length": len(safe_user_text)})
        return self._complete_turn()

    def can_continue_turn(self) -> bool:
        """Whether a failed model request can continue from persisted history."""
        if not self.messages:
            return False
        if self.messages[-1].get("role") in {"user", "tool"}:
            return True
        return (
            len(self.messages) >= 2
            and self.messages[-1].get("role") == "assistant"
            and str(self.messages[-1].get("content") or "").startswith(
                "[本地运行状态] 上一轮未完成："
            )
            and self.messages[-2].get("role") in {"user", "tool"}
        )

    def continue_turn(self) -> str:
        """Continue model inference without appending a user turn or replaying tools."""
        if not self.can_continue_turn():
            raise AgentError("当前没有可重试的未完成任务。")
        if (
            self.messages[-1].get("role") == "assistant"
            and str(self.messages[-1].get("content") or "").startswith(
                "[本地运行状态] 上一轮未完成："
            )
        ):
            # Older interactive versions closed every failed request with a
            # local-only assistant marker. Remove that marker so historical
            # sessions can resume from the preceding user/tool message.
            self.messages.pop()
            self._messages_changed()
        self.gateway.reset_budget()
        self.logger.write(
            "turn_retry",
            {
                "lastRole": self.messages[-1].get("role"),
                "messageCount": len(self.messages),
            },
        )
        return self._complete_turn()

    def _complete_turn(self) -> str:
        for round_index in range(1, self.max_rounds + 1):
            self.logger.write(
                "model_round_start",
                {"round": round_index, "maxRounds": self.max_rounds},
            )
            if self.verbose and not self.logger.has_listeners:
                print(
                    f"[DeepSeek {round_index}/{self.max_rounds}] 规划下一步…",
                    file=sys.stderr,
                )
            raw_message = self.client.complete(
                self.messages,
                tools=GATEWAY_TOOLS,
                temperature=self.temperature,
                thinking=self.thinking,
                reasoning_effort=self.reasoning_effort,
            )
            assistant = self._assistant_message(raw_message)
            self._append_message(assistant)
            reasoning = assistant.get("reasoning_content")
            if isinstance(reasoning, str) and reasoning.strip():
                self.logger.write(
                    "reasoning",
                    {"round": round_index, "content": reasoning},
                )
            tool_calls = assistant.get("tool_calls") or []
            if not tool_calls:
                content = assistant.get("content")
                if not isinstance(content, str) or not content.strip():
                    raise AgentError("DeepSeek returned neither text nor tool calls")
                self.logger.write(
                    "assistant_final",
                    {"round": round_index, "content": content},
                )
                return content
            for tool_call in tool_calls:
                call_id = str(tool_call.get("id") or f"call-{round_index}")
                function = tool_call.get("function") or {}
                name = str(function.get("name") or "")
                raw_arguments = function.get("arguments") or "{}"
                try:
                    arguments = (
                        json.loads(raw_arguments)
                        if isinstance(raw_arguments, str)
                        else raw_arguments
                    )
                    if not isinstance(arguments, dict):
                        raise ValueError("tool arguments must be an object")
                    self.logger.write(
                        "agent_tool_call",
                        {
                            "id": call_id,
                            "name": name,
                            "arguments": arguments,
                            "round": round_index,
                        },
                    )
                    result = self.gateway.dispatch(name, arguments)
                except Exception as error:
                    result = {
                        "ok": False,
                        "error": {
                            "code": "invalid_tool_call",
                            "message": str(error),
                        },
                    }
                self.logger.write(
                    "agent_tool_result",
                    {
                        "id": call_id,
                        "name": name,
                        "result": result,
                        "round": round_index,
                    },
                )
                self._append_message(
                    {
                        "role": "tool",
                        "tool_call_id": call_id,
                        "content": json.dumps(redact(result), ensure_ascii=False),
                    }
                )
        raise AgentError(
            f"Agent reached max rounds ({self.max_rounds}) without a final answer"
        )


def build_bridge(args: argparse.Namespace) -> McpHttpBridge:
    if args.transport == "adb":
        return AdbMcpHttpBridge(
            args.mcp_url,
            args.mcp_token,
            protocol_version=DEFAULT_PROTOCOL_VERSION,
            adb_host=args.adb_host,
            adb_port=args.adb_port,
            adb_key=args.adb_key,
        )
    return McpHttpBridge(args.mcp_url, args.mcp_token)


def create_runtime(
    args: argparse.Namespace,
) -> tuple[TelegramMcpSession, TelegramToolCatalog, EventLogger]:
    if not args.mcp_token:
        raise AgentError(
            "Telegram MCP token is missing. Start through "
            "Tools/MCP/run-telegram-deepseek-agent.ps1 so it can read the "
            "debug app's private token with adb run-as."
        )
    session = TelegramMcpSession(build_bridge(args))
    session.initialize()
    catalog = TelegramToolCatalog(session.tools)
    logger = EventLogger(args.trace_file)
    return session, catalog, logger


def require_api_key(args: argparse.Namespace) -> str:
    value = os.environ.get(args.api_key_env)
    if not value:
        raise AgentError(
            f"Environment variable {args.api_key_env} is not set. "
            "Use the secure PowerShell prompt described in agent/README.md."
        )
    stripped = value.strip()
    if not re.fullmatch(r"sk-[A-Za-z0-9_-]{20,}", stripped):
        raise AgentError(
            f"Environment variable {args.api_key_env} does not look like a DeepSeek API key "
            f"(trimmed length={len(stripped)}, expected prefix sk- and at least 20 key characters). "
            "Copy only the key value, without quotes, a label, or a trailing colon."
        )
    return value


def run_doctor(args: argparse.Namespace) -> dict[str, Any]:
    session, catalog, _ = create_runtime(args)
    inventory = session.inventory()
    expected_tool_count = len(inventory.get("tools") or [])
    result: dict[str, Any] = {
        "ok": (
            expected_tool_count > 0
            and len(session.tools) == expected_tool_count
            and len(catalog.descriptors) == expected_tool_count
        ),
        "mcp": {
            "tools": len(session.tools),
            "expected": expected_tool_count,
            "protocolVersion": session.server_info.get("protocolVersion"),
            "catalog": {
                "schemaVersion": inventory.get("schema_version"),
                "sourceRevision": inventory.get("source_revision"),
                "tools": len(inventory.get("tools") or []),
            },
            "tiers": dict(Counter(item.tier for item in catalog.descriptors.values())),
        },
        "deepseek": {
            "baseUrl": args.deepseek_base_url,
            "model": args.model,
            "thinking": args.thinking,
            "reasoningEffort": args.reasoning_effort,
            "apiKeyConfigured": bool(os.environ.get(args.api_key_env)),
            "apiChecked": False,
            "toolCallingChecked": False,
        },
    }
    if args.check_api or args.check_tools:
        client = DeepSeekClient(
            api_key=require_api_key(args),
            base_url=args.deepseek_base_url,
            model=args.model,
            timeout_seconds=args.api_timeout,
            retries=args.api_retries,
        )
        available_models = client.list_models()
        result["deepseek"]["apiChecked"] = True
        result["deepseek"]["availableModels"] = available_models
        result["deepseek"]["configuredModelAvailable"] = args.model in available_models
        if args.model not in available_models:
            result["ok"] = False
        if args.check_tools:
            result["deepseek"]["toolCallingChecked"] = True
            if args.model not in available_models:
                result["deepseek"]["toolCheck"] = {
                    "accepted": False,
                    "error": f"Configured model is unavailable for this key: {args.model}",
                }
            else:
                try:
                    tool_message = client.complete(
                        [
                            {
                                "role": "system",
                                "content": (
                                    "Test tool-call protocol compatibility. Call "
                                    "telegram_capabilities with an empty object before answering."
                                ),
                            },
                            {"role": "user", "content": "Run the tool protocol check."},
                        ],
                        tools=GATEWAY_TOOLS,
                        thinking=args.thinking,
                        reasoning_effort=args.reasoning_effort,
                    )
                    tool_calls = tool_message.get("tool_calls") or []
                    result["deepseek"]["toolCheck"] = {
                        "accepted": True,
                        "returnedToolCalls": len(tool_calls),
                        "toolNames": [
                            str((item.get("function") or {}).get("name") or "")
                            for item in tool_calls
                            if isinstance(item, dict)
                        ],
                        "reasoningReturned": bool(tool_message.get("reasoning_content")),
                        "contentReturned": bool(tool_message.get("content")),
                    }
                except AgentError as error:
                    result["ok"] = False
                    result["deepseek"]["toolCheck"] = {
                        "accepted": False,
                        "error": str(error),
                    }
    return result


def build_agent(
    args: argparse.Namespace,
    session: TelegramMcpSession,
    catalog: TelegramToolCatalog,
    logger: EventLogger,
    *,
    messages: list[dict[str, Any]] | None = None,
    on_messages_changed: Callable[[list[dict[str, Any]]], None] | None = None,
) -> DeepSeekTelegramAgent:
    client = DeepSeekClient(
        api_key=require_api_key(args),
        base_url=args.deepseek_base_url,
        model=args.model,
        timeout_seconds=args.api_timeout,
        retries=args.api_retries,
        retry_listener=lambda data: logger.write("deepseek_retry", data),
    )
    gateway = TelegramGateway(
        session,
        catalog,
        ApprovalPolicy(args.approval_mode),
        logger,
        max_mcp_calls=args.max_mcp_calls,
        verbose=not args.quiet,
    )
    prompt = load_system_prompt(
        args.system_prompt,
        tool_count=len(session.tools),
        model=args.model,
    )
    return DeepSeekTelegramAgent(
        client,
        gateway,
        prompt,
        max_rounds=args.max_rounds,
        temperature=args.temperature,
        thinking=args.thinking,
        reasoning_effort=args.reasoning_effort,
        verbose=not args.quiet,
        logger=logger,
        messages=messages,
        on_messages_changed=on_messages_changed,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--transport", choices=["adb", "http"], default="http")
    parser.add_argument("--mcp-url", default=os.environ.get("TELEGRAM_MCP_URL", DEFAULT_URL))
    parser.add_argument("--mcp-token", default=os.environ.get("TELEGRAM_MCP_TOKEN", DEFAULT_TOKEN))
    parser.add_argument("--adb-host", default=DEFAULT_ADB_HOST)
    parser.add_argument("--adb-port", type=int, default=DEFAULT_ADB_PORT)
    parser.add_argument("--adb-key", default=os.environ.get("TELEGRAM_ADB_KEY"))
    parser.add_argument(
        "--deepseek-base-url",
        default=os.environ.get("DEEPSEEK_BASE_URL", DEFAULT_DEEPSEEK_BASE_URL),
    )
    parser.add_argument("--model", default=os.environ.get("DEEPSEEK_MODEL", DEFAULT_DEEPSEEK_MODEL))
    parser.add_argument("--api-key-env", default="DEEPSEEK_API_KEY")
    parser.add_argument("--api-timeout", type=int, default=120)
    parser.add_argument(
        "--api-retries",
        type=int,
        default=4,
        help="DeepSeek transient HTTP/TLS retries (default: 4, for 5 total attempts)",
    )
    parser.add_argument("--system-prompt", type=Path, default=DEFAULT_PROMPT_PATH)
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument(
        "--thinking",
        choices=["enabled", "disabled"],
        default=os.environ.get("DEEPSEEK_THINKING", "enabled"),
        help="DeepSeek thinking mode; temperature is used only when disabled",
    )
    parser.add_argument(
        "--reasoning-effort",
        choices=["high", "max"],
        default=os.environ.get("DEEPSEEK_REASONING_EFFORT", "high"),
        help="Thinking effort when --thinking enabled",
    )
    parser.add_argument("--max-rounds", type=int, default=20)
    parser.add_argument("--max-mcp-calls", type=int, default=60)
    parser.add_argument(
        "--approval-mode",
        choices=["prompt", "always", "never"],
        default="prompt",
        help="prompt=ask locally for destructive calls; always=auto-approve; never=reject them",
    )
    parser.add_argument("--trace-file", type=Path)
    parser.add_argument("--quiet", action="store_true")

    subparsers = parser.add_subparsers(dest="command", required=True)
    doctor = subparsers.add_parser("doctor", help="Check MCP and optional DeepSeek connectivity")
    doctor.add_argument("--check-api", action="store_true")
    doctor.add_argument(
        "--check-tools",
        action="store_true",
        help="Also send the six gateway schemas to verify DeepSeek tool-call compatibility",
    )
    acceptance = subparsers.add_parser(
        "acceptance", help="Run authenticated MCP protocol and account-state checks"
    )
    acceptance.add_argument("--write-saved-messages", action="store_true")
    acceptance.add_argument(
        "--report",
        type=Path,
        default=REPOSITORY_ROOT
        / ".mcp-work"
        / "telegram-mcp-20260801"
        / f"runtime-validation-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}.json",
    )
    catalog = subparsers.add_parser("catalog", help="Export the enriched Telegram MCP tool catalog")
    catalog.add_argument("--json", type=Path, default=DEFAULT_CATALOG_JSON)
    catalog.add_argument("--markdown", type=Path, default=DEFAULT_CATALOG_MARKDOWN)
    tools = subparsers.add_parser("tools", help="Search the enriched catalog without using DeepSeek")
    tools.add_argument("query")
    tools.add_argument("--domain")
    tools.add_argument("--tier", choices=["preferred", "standard", "advanced", "internal"])
    tools.add_argument("--limit", type=int, default=12)
    schema = subparsers.add_parser("schema", help="Show one enriched MCP schema")
    schema.add_argument("name")
    context = subparsers.add_parser(
        "context", help="Read live Telegram account and dialog context without DeepSeek"
    )
    context.add_argument("--no-dialogs", action="store_true")
    context.add_argument("--no-me", action="store_true")
    context.add_argument("--dialog-limit", type=int, default=20)
    prompt = subparsers.add_parser("prompt", help="Print the effective system prompt")
    prompt.add_argument("--tool-guide", action="store_true")
    ask = subparsers.add_parser("ask", help="Run one natural-language task")
    ask.add_argument("task", nargs="+")
    chat = subparsers.add_parser(
        "chat",
        aliases=["serve"],
        help="Start the persistent Codex-style interactive terminal service",
    )
    chat.add_argument(
        "--session-dir",
        type=Path,
        default=DEFAULT_SESSION_DIR,
        help="Directory for isolated resumable session JSON files",
    )
    chat.add_argument(
        "--resume",
        dest="resume_session",
        help="Resume a session by list number, ID prefix, or unique title",
    )
    chat.add_argument("--no-color", action="store_true")
    chat.add_argument(
        "--hide-thinking",
        action="store_true",
        help="Start with DeepSeek reasoning display hidden",
    )
    chat.add_argument(
        "--full-tool-results",
        action="store_true",
        help="Print complete gateway and MCP results instead of summaries",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.command == "doctor":
        result = run_doctor(args)
        print(json_text(result))
        return 0 if result.get("ok") else 1
    if args.command == "acceptance":
        from telegram_mcp_acceptance import run_acceptance

        if not args.mcp_token:
            raise AgentError(
                "Telegram MCP token is missing; start through "
                "Tools/MCP/run-telegram-deepseek-agent.ps1"
            )
        exit_code, report = run_acceptance(
            url=args.mcp_url,
            token=args.mcp_token,
            report_path=args.report,
            write_saved_messages=args.write_saved_messages,
        )
        print(json_text(report["summary"]))
        print(args.report.resolve())
        return exit_code

    session, catalog, logger = create_runtime(args)
    if args.command == "catalog":
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json_text(catalog.export_json()) + "\n", encoding="utf-8")
        args.markdown.write_text(catalog.export_markdown() + "\n", encoding="utf-8")
        print(
            json_text(
                {
                    "ok": True,
                    "tools": len(catalog.descriptors),
                    "json": str(args.json),
                    "markdown": str(args.markdown),
                }
            )
        )
        return 0
    if args.command == "tools":
        print(
            json_text(
                catalog.search(
                    args.query,
                    domain=args.domain,
                    tier=args.tier,
                    limit=args.limit,
                )
            )
        )
        return 0
    if args.command == "schema":
        print(json_text(catalog.details(args.name)))
        return 0
    if args.command == "context":
        gateway = TelegramGateway(
            session,
            catalog,
            ApprovalPolicy("never"),
            logger,
            max_mcp_calls=args.max_mcp_calls,
            verbose=not args.quiet,
        )
        print(
            json_text(
                gateway.context(
                    {
                        "includeDialogs": not args.no_dialogs,
                        "includeMe": not args.no_me,
                        "dialogLimit": args.dialog_limit,
                    }
                )
            )
        )
        return 0
    if args.command == "prompt":
        print(
            load_system_prompt(
                args.system_prompt,
                tool_count=len(session.tools),
                model=args.model,
            )
        )
        if args.tool_guide:
            guide = REPOSITORY_ROOT / "agent" / "prompts" / "tool_guide_zh.md"
            print("\n\n" + guide.read_text(encoding="utf-8"))
        return 0

    if args.command in {"chat", "serve"}:
        from telegram_agent_interactive import run_interactive

        return run_interactive(args, session, catalog, logger)

    agent = build_agent(args, session, catalog, logger)
    if args.command == "ask":
        print(agent.run(" ".join(args.task)))
        return 0
    raise AssertionError(args.command)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as error:
        print(f"Telegram DeepSeek Agent failed: {error}", file=sys.stderr)
        raise SystemExit(1)

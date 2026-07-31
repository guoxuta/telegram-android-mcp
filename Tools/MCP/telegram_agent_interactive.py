#!/usr/bin/env python3
"""Persistent Codex-style terminal service for the Telegram DeepSeek agent."""

from __future__ import annotations

import json
import os
import shutil
import sys
import unicodedata
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from telegram_deepseek_agent import (
    AgentError,
    TelegramMcpSession,
    TelegramToolCatalog,
    DeepSeekTelegramAgent,
    DeepSeekRequestError,
    EventLogger,
    build_agent,
    json_text,
    load_system_prompt,
    now_iso,
    redact,
    redact_text,
)


SESSION_SCHEMA_VERSION = 1


def _parse_time(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return datetime.min.replace(tzinfo=UTC)


def _safe_session_value(value: Any, key: str = "") -> Any:
    """Return a JSON-compatible, credential-redacted session value."""
    safe = redact(value, key)
    if isinstance(safe, str):
        return redact_text(safe)
    if isinstance(safe, dict):
        return {
            str(child_key): _safe_session_value(child_value, str(child_key))
            for child_key, child_value in safe.items()
        }
    if isinstance(safe, list):
        return [_safe_session_value(item, key) for item in safe]
    if safe is None or isinstance(safe, (bool, int, float)):
        return safe
    return str(safe)


@dataclass
class SessionRecord:
    session_id: str
    title: str
    created_at: str
    updated_at: str
    model: str
    thinking: str
    reasoning_effort: str
    messages: list[dict[str, Any]]
    auto_title: bool = True

    @property
    def turn_count(self) -> int:
        return sum(message.get("role") == "user" for message in self.messages)

    @property
    def short_id(self) -> str:
        return self.session_id[:8]

    @property
    def last_user_text(self) -> str:
        for message in reversed(self.messages):
            if message.get("role") == "user" and isinstance(
                message.get("content"), str
            ):
                return str(message["content"])
        return ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": SESSION_SCHEMA_VERSION,
            "id": self.session_id,
            "title": self.title,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "model": self.model,
            "thinking": self.thinking,
            "reasoningEffort": self.reasoning_effort,
            "autoTitle": self.auto_title,
            "turnCount": self.turn_count,
            "messages": _safe_session_value(self.messages),
        }

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "SessionRecord":
        if int(value.get("schemaVersion", 0)) != SESSION_SCHEMA_VERSION:
            raise AgentError("Unsupported session schema version")
        session_id = str(value.get("id") or "")
        messages = value.get("messages")
        if not session_id or not isinstance(messages, list):
            raise AgentError("Invalid session file: id/messages missing")
        clean_messages = [
            item for item in messages if isinstance(item, dict) and item.get("role")
        ]
        return cls(
            session_id=session_id,
            title=str(value.get("title") or f"会话 {session_id[:8]}"),
            created_at=str(value.get("createdAt") or now_iso()),
            updated_at=str(value.get("updatedAt") or now_iso()),
            model=str(value.get("model") or "unknown"),
            thinking=str(value.get("thinking") or "enabled"),
            reasoning_effort=str(value.get("reasoningEffort") or "high"),
            messages=clean_messages,
            auto_title=bool(value.get("autoTitle", False)),
        )


class SessionStore:
    """Atomic JSON session storage with prefix/index based resume lookup."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.load_errors: list[str] = []

    def path_for(self, session_id: str) -> Path:
        if not session_id or any(
            character not in "0123456789abcdef" for character in session_id.lower()
        ):
            raise AgentError(f"Invalid session ID: {session_id!r}")
        return self.root / f"{session_id}.json"

    def create(
        self,
        *,
        model: str,
        thinking: str,
        reasoning_effort: str,
        title: str | None = None,
    ) -> SessionRecord:
        timestamp = now_iso()
        explicit_title = bool(title and title.strip())
        record = SessionRecord(
            session_id=uuid.uuid4().hex,
            title=(title or datetime.now().strftime("新会话 %Y-%m-%d %H:%M")).strip()[:80],
            created_at=timestamp,
            updated_at=timestamp,
            model=model,
            thinking=thinking,
            reasoning_effort=reasoning_effort,
            messages=[],
            auto_title=not explicit_title,
        )
        self.save(record, touch=False)
        return record

    def save(self, record: SessionRecord, *, touch: bool = True) -> None:
        if touch:
            record.updated_at = now_iso()
        target = self.path_for(record.session_id)
        temporary = target.with_name(f".{record.session_id}.{uuid.uuid4().hex}.tmp")
        payload = json.dumps(record.to_dict(), ensure_ascii=False, indent=2) + "\n"
        try:
            temporary.write_text(payload, encoding="utf-8")
            os.replace(temporary, target)
        finally:
            if temporary.exists():
                temporary.unlink()

    def load(self, session_id: str) -> SessionRecord:
        path = self.path_for(session_id)
        if not path.is_file():
            raise AgentError(f"Session not found: {session_id}")
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise AgentError(f"Cannot read session {session_id}: {error}") from error
        if not isinstance(value, dict):
            raise AgentError(f"Invalid session JSON: {session_id}")
        return SessionRecord.from_dict(value)

    def discard_if_empty(self, record: SessionRecord | None) -> bool:
        """Avoid accumulating placeholder sessions that never received input."""
        if record is None or record.turn_count or not record.auto_title:
            return False
        path = self.path_for(record.session_id)
        if path.exists():
            path.unlink()
        return True

    def list(self) -> list[SessionRecord]:
        records: list[SessionRecord] = []
        self.load_errors = []
        for path in self.root.glob("*.json"):
            try:
                records.append(self.load(path.stem))
            except AgentError as error:
                self.load_errors.append(str(error))
        records.sort(key=lambda item: _parse_time(item.updated_at), reverse=True)
        return records

    def resolve(self, reference: str) -> SessionRecord:
        reference = reference.strip()
        if not reference:
            raise AgentError("Session reference is empty")
        records = self.list()
        if reference.isdigit():
            index = int(reference)
            if 1 <= index <= len(records):
                return records[index - 1]
        exact = [item for item in records if item.session_id == reference]
        if exact:
            return exact[0]
        prefix = [item for item in records if item.session_id.startswith(reference.lower())]
        if len(prefix) == 1:
            return prefix[0]
        lowered = reference.casefold()
        title_exact = [item for item in records if item.title.casefold() == lowered]
        if len(title_exact) == 1:
            return title_exact[0]
        title_contains = [item for item in records if lowered in item.title.casefold()]
        if len(title_contains) == 1:
            return title_contains[0]
        matches = prefix or title_exact or title_contains
        if matches:
            raise AgentError(
                "Session reference is ambiguous: "
                + ", ".join(f"{item.short_id} ({item.title})" for item in matches[:8])
            )
        raise AgentError(f"No session matches: {reference}")


class TerminalRenderer:
    """Dependency-free ANSI terminal renderer for structured Agent events."""

    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    CYAN = "\033[36m"
    BLUE = "\033[34m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    MAGENTA = "\033[35m"
    RED = "\033[31m"

    def __init__(
        self,
        *,
        color: bool = True,
        show_thinking: bool = True,
        full_tool_results: bool = False,
    ) -> None:
        self.color = bool(color and sys.stdout.isatty() and "NO_COLOR" not in os.environ)
        self.show_thinking = show_thinking
        self.full_tool_results = full_tool_results

    @property
    def width(self) -> int:
        return max(60, min(shutil.get_terminal_size((100, 30)).columns, 140))

    def styled(self, text: str, *styles: str) -> str:
        if not self.color:
            return text
        return "".join(styles) + text + self.RESET

    @staticmethod
    def _display_width(value: str) -> int:
        width = 0
        for character in value:
            if unicodedata.combining(character):
                continue
            width += 2 if unicodedata.east_asian_width(character) in {"W", "F"} else 1
        return width

    @classmethod
    def _wrap_display_width(cls, value: str, width: int) -> list[str]:
        if not value:
            return [""]
        lines: list[str] = []
        current: list[str] = []
        current_width = 0
        for character in value:
            character_width = cls._display_width(character)
            if current and current_width + character_width > width:
                lines.append("".join(current))
                current = []
                current_width = 0
            current.append(character)
            current_width += character_width
        if current:
            lines.append("".join(current))
        return lines or [""]

    @classmethod
    def _clip_display_width(cls, value: str, width: int) -> str:
        if cls._display_width(value) <= width:
            return value
        target = max(1, width - 1)
        current: list[str] = []
        current_width = 0
        for character in value:
            character_width = cls._display_width(character)
            if current_width + character_width > target:
                break
            current.append(character)
            current_width += character_width
        return "".join(current) + "…"

    def _wrapped_lines(self, content: str, width: int) -> list[str]:
        lines: list[str] = []
        for raw_line in content.splitlines() or [""]:
            if not raw_line:
                lines.append("")
                continue
            # textwrap counts code points, while CJK glyphs occupy two terminal
            # cells.  Wrap by display width so the box remains aligned in a
            # Chinese Windows terminal.
            lines.extend(self._wrap_display_width(raw_line, max(20, width)))
        return lines

    def panel(self, title: str, content: str, color: str = "") -> None:
        inner = self.width - 4
        top_inner = self.width - 2
        clean_title = f" {self._clip_display_width(title, top_inner - 2)} "
        top = (
            "╭"
            + clean_title
            + "─" * max(0, top_inner - self._display_width(clean_title))
            + "╮"
        )
        print(self.styled(top, color, self.BOLD))
        for line in self._wrapped_lines(content, inner):
            padding = " " * max(0, inner - self._display_width(line))
            print(self.styled("│ ", color) + line + padding + self.styled(" │", color))
        print(self.styled("╰" + "─" * (self.width - 2) + "╯", color))

    def header(self, record: SessionRecord, tool_count: int) -> None:
        self.panel(
            "Telegram DeepSeek Agent",
            "\n".join(
                (
                    f"会话：{record.short_id}  {record.title}",
                    f"模型：{record.model}  Thinking={record.thinking}/{record.reasoning_effort}",
                    f"工具：6 个 Agent 网关 → {tool_count} 个真实 MCP",
                    "输入 /help 查看命令；Ctrl+C 中断，/quit 退出。",
                )
            ),
            self.BLUE,
        )

    def session_changed(self, record: SessionRecord, action: str) -> None:
        print(
            self.styled(
                f"\n◆ {action}：{record.short_id}  {record.title}（{record.turn_count} 轮）",
                self.GREEN,
                self.BOLD,
            )
        )

    def prompt(self) -> str:
        return input(self.styled("\n你 › ", self.CYAN, self.BOLD))

    def info(self, message: str) -> None:
        print(self.styled(f"\nℹ {message}", self.BLUE))

    def warning(self, message: str) -> None:
        print(self.styled(f"\n⚠ {message}", self.YELLOW, self.BOLD))

    def error(self, message: str) -> None:
        print(self.styled(f"\n✗ {message}", self.RED, self.BOLD), file=sys.stderr)

    @staticmethod
    def _status(result: Any) -> tuple[str, str]:
        if not isinstance(result, dict):
            return "·", "返回非对象结果"
        ok = result.get("ok")
        error = result.get("error")
        if ok is False or error:
            if isinstance(error, dict):
                code = error.get("code") or "error"
                message = error.get("message") or ""
                return "✗", f"{code}: {message}".strip()
            return "✗", str(error or "调用失败")
        if isinstance(result.get("results"), list):
            names = [
                str(item.get("name"))
                for item in result["results"]
                if isinstance(item, dict) and item.get("name")
            ]
            if names:
                suffix = " …" if len(names) > 6 else ""
                return "✓", f"返回 {len(result['results'])} 项：{', '.join(names[:6])}{suffix}"
            return "✓", f"返回 {len(result['results'])} 项"
        data = result.get("data")
        if isinstance(data, dict):
            identity = data.get("id") or data.get("viewId") or data.get("rowId")
            name = data.get("name") or data.get("title")
            if identity or name:
                return "✓", "，".join(
                    part
                    for part in (
                        f"name={name}" if name else "",
                        f"id={identity}" if identity else "",
                    )
                    if part
                )
            return "✓", "data: " + ", ".join(str(key) for key in list(data)[:8])
        return "✓", "调用完成" if ok is not False else "调用失败"

    def _print_result(self, title: str, result: Any, color: str) -> None:
        icon, summary = self._status(result)
        status_color = self.GREEN if icon == "✓" else self.RED if icon == "✗" else color
        print(self.styled(f"  {icon} {title} — {summary}", status_color))
        if self.full_tool_results:
            self.panel(f"{title} 返回", json_text(result), color)

    def handle(self, event: str, data: dict[str, Any]) -> None:
        if event == "model_round_start":
            print(
                self.styled(
                    f"\n◇ DeepSeek {data.get('round')}/{data.get('maxRounds')} 正在思考…",
                    self.CYAN,
                    self.BOLD,
                )
            )
        elif event == "deepseek_retry":
            self.warning(
                "DeepSeek 连接暂时失败"
                f"（{data.get('errorType') or 'network error'}），"
                f"{data.get('delaySeconds')} 秒后自动重试 "
                f"{data.get('nextAttempt')}/{data.get('maxAttempts')}…"
            )
        elif event == "turn_retry":
            self.info("正在从已保存的模型/MCP结果继续，已完成的调用不会在本地重放。")
        elif event == "reasoning" and self.show_thinking:
            self.panel(
                f"思考过程 · 第 {data.get('round')} 步",
                str(data.get("content") or ""),
                self.CYAN,
            )
        elif event == "agent_tool_call":
            name = str(data.get("name") or "unknown")
            arguments = json_text(data.get("arguments") or {})
            self.panel(f"Agent 工具调用 → {name}", arguments, self.YELLOW)
        elif event == "agent_tool_result":
            self._print_result(
                f"网关 {data.get('name')}", data.get("result"), self.YELLOW
            )
        elif event == "mcp_call":
            content = "\n".join(
                (
                    f"目的：{data.get('purpose') or '(未说明)'}",
                    f"风险：{data.get('risk')}  审批：{data.get('approval')}",
                    "参数：" + json_text(data.get("arguments") or {}, indent=None),
                )
            )
            self.panel(
                f"真实 MCP {data.get('number')}/{data.get('budget')} → {data.get('tool')}",
                content,
                self.MAGENTA,
            )
        elif event == "mcp_result":
            self._print_result(
                f"MCP {data.get('tool')}", data.get("result"), self.MAGENTA
            )
        elif event == "mcp_rejected":
            self.warning(f"MCP 调用被拒绝：{data.get('tool')}")
        elif event == "gateway_error":
            self.error(str(data.get("error") or data))
        elif event == "secret_redacted":
            self.warning(str(data.get("message") or "疑似秘密已脱敏"))
        elif event == "assistant_final":
            self.panel("Agent 最终答复", str(data.get("content") or ""), self.GREEN)

    def sessions(self, records: list[SessionRecord], current_id: str | None) -> None:
        if not records:
            self.info("没有历史会话。")
            return
        print(self.styled("\n历史会话", self.BLUE, self.BOLD))
        print("  #  当前  ID        更新时间           轮次  标题")
        for index, record in enumerate(records, 1):
            current = "●" if record.session_id == current_id else " "
            updated = _parse_time(record.updated_at).astimezone().strftime("%Y-%m-%d %H:%M")
            title = record.title.replace("\n", " ")[:48]
            print(
                f"  {index:<2} {current:^4}  {record.short_id:<8}  {updated:<16}  "
                f"{record.turn_count:<4}  {title}"
            )

    def history(self, record: SessionRecord, limit: int = 20) -> None:
        conversational = [
            item
            for item in record.messages
            if item.get("role") in {"user", "assistant"}
            and isinstance(item.get("content"), str)
            and str(item.get("content")).strip()
        ][-max(1, limit) :]
        if not conversational:
            self.info("当前会话还没有对话。")
            return
        print(self.styled(f"\n会话历史 · {record.short_id}", self.BLUE, self.BOLD))
        for message in conversational:
            role = "你" if message["role"] == "user" else "Agent"
            content = str(message["content"]).strip().replace("\n", " ")
            if len(content) > 240:
                content = content[:237] + "…"
            print(f"  {role:<5} {content}")


class InteractiveAgentService:
    def __init__(
        self,
        args: Any,
        mcp_session: TelegramMcpSession,
        catalog: TelegramToolCatalog,
        logger: EventLogger,
        store: SessionStore,
        renderer: TerminalRenderer,
    ) -> None:
        self.args = args
        self.mcp_session = mcp_session
        self.catalog = catalog
        self.logger = logger
        self.store = store
        self.renderer = renderer
        self.record: SessionRecord | None = None
        self.agent: DeepSeekTelegramAgent | None = None
        self.system_prompt = load_system_prompt(
            args.system_prompt,
            tool_count=len(mcp_session.tools),
            model=args.model,
        )
        self.logger.subscribe(renderer.handle)

    def _derive_title(self, record: SessionRecord) -> None:
        if not record.auto_title or record.turn_count < 1:
            return
        first_user = next(
            (
                str(message.get("content") or "")
                for message in record.messages
                if message.get("role") == "user"
            ),
            "",
        )
        title = " ".join(first_user.strip().split())
        if title:
            record.title = (title[:47] + "…") if len(title) > 48 else title
            record.auto_title = False

    def _activate(self, record: SessionRecord, action: str) -> None:
        previous = self.record
        if previous is not None and previous.session_id != record.session_id:
            self.store.discard_if_empty(previous)
        self.record = record

        def save_messages(messages: list[dict[str, Any]]) -> None:
            record.messages = _safe_session_value(messages)
            self._derive_title(record)
            self.store.save(record)

        self.agent = build_agent(
            self.args,
            self.mcp_session,
            self.catalog,
            self.logger,
            messages=record.messages,
            on_messages_changed=save_messages,
        )
        record.messages = _safe_session_value(self.agent.messages)
        record.model = self.args.model
        record.thinking = self.args.thinking
        record.reasoning_effort = self.args.reasoning_effort
        self.store.save(record)
        self.renderer.session_changed(record, action)

    def new(self, title: str | None = None) -> None:
        record = self.store.create(
            model=self.args.model,
            thinking=self.args.thinking,
            reasoning_effort=self.args.reasoning_effort,
            title=title,
        )
        self._activate(record, "已创建新会话")

    def resume(self, reference: str) -> None:
        record = self.store.resolve(reference)
        self._activate(record, "已恢复会话")

    def _resume_interactive(self) -> None:
        records = self.store.list()
        if self.record and self.record.turn_count == 0 and self.record.auto_title:
            records = [
                item for item in records if item.session_id != self.record.session_id
            ]
        self.renderer.sessions(records, self.record.session_id if self.record else None)
        if not records:
            return
        try:
            reference = input(
                self.renderer.styled(
                    "选择编号、ID 前缀或标题（回车取消）› ",
                    self.renderer.CYAN,
                )
            ).strip()
        except EOFError:
            return
        if reference:
            self.resume(reference)

    def _status(self) -> None:
        if self.record is None:
            return
        self.renderer.panel(
            "当前会话",
            "\n".join(
                (
                    f"ID：{self.record.session_id}",
                    f"标题：{self.record.title}",
                    f"轮次：{self.record.turn_count}",
                    f"模型：{self.args.model}",
                    f"Thinking：{self.args.thinking}/{self.args.reasoning_effort}",
                    f"思考显示：{'on' if self.renderer.show_thinking else 'off'}",
                    f"工具结果：{'full' if self.renderer.full_tool_results else 'summary'}",
                    f"会话目录：{self.store.root}",
                )
            ),
            self.renderer.BLUE,
        )

    def _help(self) -> None:
        self.renderer.panel(
            "交互命令",
            "\n".join(
                (
                    "/new [标题]              创建并切换到独立新会话",
                    "/resume [编号|ID|标题]   恢复历史会话；不带参数时交互选择",
                    "/sessions                列出历史会话",
                    "/history [数量]          查看当前会话最近的问答",
                    "/retry                   从网络中断处继续，不重复本地重放已完成的 MCP",
                    "/rename <标题>           重命名当前会话",
                    "/status                  查看当前会话和运行配置",
                    "/show-thinking on|off    显示或隐藏 DeepSeek 思考过程",
                    "/results summary|full    切换 MCP/网关结果显示粒度",
                    "/paste                   输入多行任务，以单独一行 /end 结束",
                    "/clear                   清屏并重画会话信息",
                    "/help                    显示本帮助",
                    "/quit                    保存并退出",
                )
            ),
            self.renderer.BLUE,
        )

    def _paste(self) -> str:
        self.renderer.info("进入多行输入；以单独一行 /end 结束，/cancel 取消。")
        lines: list[str] = []
        while True:
            try:
                line = input("… ")
            except EOFError:
                break
            if line == "/end":
                break
            if line == "/cancel":
                return ""
            lines.append(line)
        return "\n".join(lines).strip()

    def _command(self, value: str) -> bool:
        command, _, argument = value.partition(" ")
        command = command.lower()
        argument = argument.strip()
        if command in {"/quit", "/exit"}:
            self.store.discard_if_empty(self.record)
            self.renderer.info("会话已保存。")
            return False
        if command in {"/new", "/reset"}:
            self.new(argument or None)
        elif command == "/resume":
            if argument:
                self.resume(argument)
            else:
                self._resume_interactive()
        elif command in {"/sessions", "/list"}:
            records = self.store.list()
            self.renderer.sessions(records, self.record.session_id if self.record else None)
            for error in self.store.load_errors:
                self.renderer.warning(error)
        elif command == "/retry":
            if argument:
                raise AgentError("用法：/retry")
            if self.agent is None:
                raise AgentError("当前没有可重试的会话。")
            self.agent.continue_turn()
        elif command == "/history":
            try:
                limit = int(argument) if argument else 20
            except ValueError as error:
                raise AgentError("/history 参数必须是整数") from error
            if self.record:
                self.renderer.history(self.record, limit)
        elif command == "/rename":
            if not argument:
                raise AgentError("用法：/rename <新标题>")
            if self.record:
                self.record.title = argument[:80]
                self.record.auto_title = False
                self.store.save(self.record)
                self.renderer.session_changed(self.record, "会话已重命名")
        elif command in {"/status", "/session"}:
            self._status()
        elif command == "/show-thinking":
            if argument not in {"on", "off"}:
                raise AgentError("用法：/show-thinking on|off")
            self.renderer.show_thinking = argument == "on"
            self.renderer.info(f"思考过程显示已设为 {argument}。")
        elif command == "/results":
            if argument not in {"summary", "full"}:
                raise AgentError("用法：/results summary|full")
            self.renderer.full_tool_results = argument == "full"
            self.renderer.info(f"工具结果显示已设为 {argument}。")
        elif command == "/clear":
            print("\033[2J\033[H", end="")
            if self.record:
                self.renderer.header(self.record, len(self.mcp_session.tools))
        elif command == "/help":
            self._help()
        else:
            raise AgentError(f"未知命令：{command}。输入 /help 查看命令。")
        return True

    def run(self, resume_reference: str | None = None) -> int:
        if resume_reference:
            try:
                self.resume(resume_reference)
            except AgentError as error:
                self.renderer.warning(f"无法恢复指定会话：{error}；将创建新会话。")
                self.new()
        else:
            self.new()
        assert self.record is not None
        self.renderer.header(self.record, len(self.mcp_session.tools))
        while True:
            try:
                value = self.renderer.prompt().strip()
            except EOFError:
                print()
                self.store.discard_if_empty(self.record)
                self.renderer.info("输入结束，会话已保存。")
                return 0
            except KeyboardInterrupt:
                print()
                self.renderer.info("再次输入任务，或使用 /quit 退出。")
                continue
            if not value:
                continue
            try:
                if value.startswith("/"):
                    if value == "/paste":
                        value = self._paste()
                        if not value:
                            continue
                    else:
                        if not self._command(value):
                            return 0
                        continue
                assert self.agent is not None
                if self.agent.can_continue_turn():
                    self.agent.abort_pending_turn("用户开始了新任务，未继续上一轮")
                self.agent.run(value)
            except KeyboardInterrupt:
                print()
                if self.agent is not None:
                    self.agent.abort_pending_turn("用户中断当前操作")
                self.renderer.warning("当前操作已由用户中断；已保存此前完整消息。")
            except DeepSeekRequestError as error:
                if self.agent is not None and error.retryable and self.agent.can_continue_turn():
                    self.renderer.error(f"Agent network error: {error}")
                    self.renderer.warning(
                        "DeepSeek 网络重试已耗尽；任务断点已保存。网络恢复后输入 /retry 继续，"
                        "无需重新输入任务。"
                    )
                else:
                    if self.agent is not None:
                        self.agent.abort_pending_turn(str(error))
                    self.renderer.error(f"Agent error: {error}")
            except Exception as error:
                if self.agent is not None:
                    self.agent.abort_pending_turn(str(error))
                self.renderer.error(f"Agent error: {error}")


def run_interactive(
    args: Any,
    mcp_session: TelegramMcpSession,
    catalog: TelegramToolCatalog,
    logger: EventLogger,
) -> int:
    store = SessionStore(args.session_dir)
    renderer = TerminalRenderer(
        color=not args.no_color,
        show_thinking=not args.hide_thinking,
        full_tool_results=args.full_tool_results,
    )
    service = InteractiveAgentService(
        args,
        mcp_session,
        catalog,
        logger,
        store,
        renderer,
    )
    return service.run(args.resume_session)

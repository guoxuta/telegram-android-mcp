#!/usr/bin/env python3
"""Closed-loop acceptance checks for the Telegram Android MCP endpoint.

The default run is non-mutating and is valid both before and after login. Pass
``--write-saved-messages`` only for a disposable test account; it exercises
reversible account settings plus real Saved Messages and owned-forum workflows,
verifies them by independent readback, and removes the generated fixtures.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


PROTOCOL_VERSION = "2025-06-18"
CATALOG_URI = "telegram://mcp/tool-catalog"

REPORT_REDACTED_KEYS = {
    "access_hash",
    "auth_key",
    "display_name",
    "dialog_id",
    "first_name",
    "invite_link",
    "last_name",
    "message_id",
    "message_ids",
    "operation_id",
    "peer",
    "phone",
    "phone_number",
    "token",
    "topic_id",
    "story_id",
    "user_id",
    "username",
}
REPORT_PEER_PATTERN = re.compile(
    r"^(?:user|chat|channel|dialog):-?\d+$", re.IGNORECASE
)
REPORT_PEER_SUBSTRING_PATTERN = re.compile(
    r"\b(?:user|chat|channel|dialog):-?\d+\b", re.IGNORECASE
)
REPORT_PHONE_SUBSTRING_PATTERN = re.compile(r"(?<!\w)\+\d{7,15}(?!\w)")
REPORT_USERNAME_SUBSTRING_PATTERN = re.compile(r"(?<!\w)@[A-Za-z0-9_]{5,32}\b")
REPORT_TOKEN_SUBSTRING_PATTERN = re.compile(r"\b[0-9a-fA-F]{64}\b")
ZERO_ARGUMENT_CONDITIONAL_CODES = {
    "FEATURE_UNAVAILABLE",
    "NOT_LOGGED_IN",
    "PERMISSION_REQUIRED",
    "PREMIUM_REQUIRED",
}


def redact_report_value(value: Any, key: str = "") -> Any:
    """Remove account identity and reusable access material from evidence files."""
    normalized_key = key.lower()
    if normalized_key in REPORT_REDACTED_KEYS:
        if value in (None, ""):
            return value
        return "<redacted>"
    if (
        isinstance(value, str)
        and (normalized_key == "sha256" or normalized_key.endswith("_sha256"))
        and REPORT_TOKEN_SUBSTRING_PATTERN.fullmatch(value)
    ):
        # Content/catalog/APK digests are verification evidence, not bearer secrets.
        return value
    if isinstance(value, dict):
        return {
            child_key: redact_report_value(child_value, child_key)
            for child_key, child_value in value.items()
        }
    if isinstance(value, list):
        return [redact_report_value(item, key) for item in value]
    if isinstance(value, str):
        if REPORT_PEER_PATTERN.fullmatch(value):
            return "<redacted-peer>"
        value = REPORT_PEER_SUBSTRING_PATTERN.sub("<redacted-peer>", value)
        value = REPORT_PHONE_SUBSTRING_PATTERN.sub("<redacted-phone>", value)
        value = REPORT_USERNAME_SUBSTRING_PATTERN.sub("<redacted-username>", value)
        value = REPORT_TOKEN_SUBSTRING_PATTERN.sub("<redacted-token>", value)
    return value


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def safe_tool_error_code(result: dict[str, Any]) -> str:
    error = result.get("error") or {}
    return str(error.get("code") or "UNKNOWN")


def validate_tool_envelope(
    name: str, structured: dict[str, Any], is_error: Any = None
) -> None:
    """Enforce the discriminated success/error envelope on every runtime call."""
    if type(structured.get("ok")) is not bool:
        raise RuntimeError(f"{name} returned a non-boolean ok discriminator")
    if structured["ok"]:
        if set(structured) != {"ok", "data"} or not isinstance(
            structured.get("data"), dict
        ):
            raise RuntimeError(f"{name} returned an invalid success envelope")
        if is_error is True:
            raise RuntimeError(f"{name} returned ok=true with isError=true")
        return
    error = structured.get("error")
    if set(structured) != {"ok", "error"} or not isinstance(error, dict):
        raise RuntimeError(f"{name} returned an invalid error envelope")
    if not isinstance(error.get("code"), str) or not error["code"]:
        raise RuntimeError(f"{name} returned an error without a code")
    if not isinstance(error.get("message"), str) or not error["message"]:
        raise RuntimeError(f"{name} returned an error without a message")
    if type(error.get("retryable")) is not bool:
        raise RuntimeError(f"{name} returned an error without retryable boolean")
    if not set(error).issubset({"code", "message", "retryable", "details"}):
        raise RuntimeError(f"{name} returned unknown error-envelope fields")
    if is_error is False:
        raise RuntimeError(f"{name} returned ok=false with isError=false")


def schema_probe_value(schema: dict[str, Any]) -> Any:
    if "const" in schema:
        return schema["const"]
    values = schema.get("enum") or []
    if values:
        return values[0]
    value_type = schema.get("type")
    if value_type == "boolean":
        return False
    if value_type == "integer":
        return int(schema.get("minimum", 0))
    if value_type == "number":
        return float(schema.get("minimum", 0))
    if value_type == "array":
        count = max(1, int(schema.get("minItems", 0)))
        return [schema_probe_value(schema.get("items") or {}) for _ in range(count)]
    if value_type == "object":
        properties = schema.get("properties") or {}
        return {
            name: schema_probe_value(properties.get(name) or {})
            for name in schema.get("required") or []
        }
    minimum = max(1, int(schema.get("minLength", 1)))
    return "x" * minimum


class RpcClient:
    def __init__(self, url: str, token: str | None) -> None:
        self.url = url
        self.token = token or ""
        self.request_id = 0
        self.session_id = ""
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def wait_until_ready(self, timeout_seconds: int = 45) -> None:
        health_url = self.url.removesuffix("/mcp") + "/health"
        deadline = time.monotonic() + timeout_seconds
        last_error = "endpoint unavailable"
        while time.monotonic() < deadline:
            headers = {"Accept": "application/json"}
            if self.token:
                headers["Authorization"] = f"Bearer {self.token}"
            request = urllib.request.Request(
                health_url, headers=headers, method="GET"
            )
            try:
                with self.opener.open(request, timeout=2) as response:
                    if response.status == 200:
                        return
                    last_error = f"HTTP {response.status}"
            except Exception as error:  # Startup may reset the forwarded socket.
                last_error = error.__class__.__name__
            time.sleep(0.5)
        raise RuntimeError(
            "Telegram MCP health endpoint did not become ready within "
            f"{timeout_seconds} seconds ({last_error})"
        )

    def post_raw(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        request = urllib.request.Request(
            self.url,
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=310) as response:
                status = response.status
                raw = response.read()
                response_headers = response.headers
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read()
            response_headers = error.headers
        returned_session = response_headers.get("Mcp-Session-Id") if response_headers else None
        if returned_session:
            self.session_id = returned_session
        return status, json.loads(raw.decode("utf-8")) if raw else None

    def rpc(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        self.request_id += 1
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self.request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params
        status, response = self.post_raw(payload)
        if status != 200 or not isinstance(response, dict):
            raise RuntimeError(f"{method} failed with HTTP {status}: {response}")
        if response.get("error"):
            raise RuntimeError(f"{method} JSON-RPC error: {response['error']}")
        result = response.get("result")
        if not isinstance(result, dict):
            raise RuntimeError(f"{method} returned no object result")
        return result

    def call(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        result = self.rpc(
            "tools/call", {"name": name, "arguments": arguments or {}}
        )
        structured = result.get("structuredContent")
        if not isinstance(structured, dict):
            raise RuntimeError(f"{name} returned no structuredContent")
        validate_tool_envelope(name, structured, result.get("isError"))
        return structured


class Acceptance:
    def __init__(self, client: RpcClient, report_path: Path) -> None:
        self.client = client
        self.report_path = report_path
        self.cleanup_message_ids: list[int] = []
        self.cleanup_chat_peer = ""
        self.cleanup_folder_ids: list[int] = []
        self.cleanup_file_refs: list[str] = []
        self.cleanup_upload_refs: list[str] = []
        self.report: dict[str, Any] = {
            "schema_version": 1,
            "run_id": f"telegram-runtime-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}",
            "started_at": now_iso(),
            "endpoint": client.url,
            "token_persisted": False,
            "checks": [],
            "tool_evidence": {},
        }

    def check(
        self,
        name: str,
        status: str,
        evidence: Any = None,
        error: str | None = None,
    ) -> None:
        item: dict[str, Any] = {"name": name, "status": status}
        if evidence is not None:
            item["evidence"] = evidence
        if error:
            item["error"] = error
        self.report["checks"].append(item)

    def tool(self, name: str, status: str, evidence: Any = None) -> None:
        entry: dict[str, Any] = {"status": status}
        if evidence is not None:
            entry["evidence"] = evidence
        self.report["tool_evidence"][name] = entry

    def call_ok(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        for attempt in range(4):
            result = self.client.call(name, arguments)
            if result.get("ok") is True:
                return result
            error = result.get("error") or {}
            details = error.get("details") or {}
            retry_after = details.get("retry_after_seconds")
            confirmed_flood_rejection = (
                error.get("code") == "TELEGRAM_ERROR"
                and error.get("retryable") is True
                and isinstance(retry_after, int)
                and retry_after >= 0
            )
            if confirmed_flood_rejection and attempt < 3:
                delay = min(300, max(1, retry_after + 1))
                self.check(
                    f"rate-limit-retry:{name}",
                    "passed",
                    {
                        "attempt": attempt + 1,
                        "retry_after_seconds": retry_after,
                        "waited_seconds": delay,
                    },
                )
                time.sleep(delay)
                continue
            raise RuntimeError(f"{name} failed with {safe_tool_error_code(result)}")
        raise AssertionError("unreachable")

    def protocol(self, token: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        unauthorized = RpcClient(self.client.url, None)
        status, body = unauthorized.post_raw(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {"protocolVersion": PROTOCOL_VERSION},
            }
        )
        unauthorized_code = (
            ((body or {}).get("error") or {}).get("code")
            if isinstance(body, dict)
            else None
        )
        if status != 401 or unauthorized_code != "UNAUTHORIZED":
            raise RuntimeError(
                f"unauthorized request expected 401/UNAUTHORIZED, got {status}/{body}"
            )
        serialized = json.dumps(body, ensure_ascii=False)
        if token and token in serialized:
            raise RuntimeError("unauthorized response leaked bearer token")
        self.check("unauthorized-denied", "passed", {"http": status})

        initialized = self.client.rpc(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "telegram-mcp-acceptance", "version": "1.0.0"},
            },
        )
        if initialized.get("protocolVersion") != PROTOCOL_VERSION:
            raise RuntimeError(f"unexpected protocol version: {initialized}")
        notification_status, notification_body = self.client.post_raw(
            {"jsonrpc": "2.0", "method": "notifications/initialized"}
        )
        if notification_status != 202 or notification_body is not None:
            raise RuntimeError(
                "notifications/initialized expected HTTP 202 with no body, got "
                f"{notification_status}/{notification_body}"
            )
        self.check("initialize", "passed", initialized.get("serverInfo"))

        listed = self.client.rpc("tools/list")
        tools = listed.get("tools") or []
        names = [item.get("name") for item in tools if isinstance(item, dict)]
        if not tools or len(set(names)) != len(tools):
            raise RuntimeError(
                f"expected a non-empty unique tool list, got {len(tools)}/{len(set(names))}"
            )
        for tool in tools:
            schema = tool.get("inputSchema") or {}
            properties = schema.get("properties") or {}
            required = schema.get("required") or []
            if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
                raise RuntimeError(f"unsafe input schema for {tool.get('name')}: {schema}")
            output_schema = tool.get("outputSchema") or {}
            variants = output_schema.get("oneOf") or []
            if len(variants) != 2:
                raise RuntimeError(
                    f"missing discriminated output schema for {tool.get('name')}"
                )
            success, failure = variants
            success_properties = success.get("properties") or {}
            failure_properties = failure.get("properties") or {}
            if (
                (success_properties.get("ok") or {}).get("const") is not True
                or set(success.get("required") or []) != {"ok", "data"}
                or (failure_properties.get("ok") or {}).get("const") is not False
                or set(failure.get("required") or []) != {"ok", "error"}
            ):
                raise RuntimeError(
                    f"invalid output discriminator for {tool.get('name')}"
                )
            if not set(required).issubset(properties):
                raise RuntimeError(f"required/property mismatch for {tool.get('name')}")
            self.tool(
                str(tool.get("name")),
                "registered-schema-verified",
                {
                    "readOnly": bool((tool.get("annotations") or {}).get("readOnlyHint")),
                    "destructive": bool((tool.get("annotations") or {}).get("destructiveHint")),
                },
            )
        self.check("tools-list-and-schemas", "passed", {"tools": len(tools)})

        resource = self.client.rpc("resources/read", {"uri": CATALOG_URI})
        contents = resource.get("contents") or []
        catalog = json.loads(contents[0]["text"])
        catalog_names = {item.get("name") for item in catalog.get("tools") or []}
        if catalog_names != set(names):
            raise RuntimeError("resource catalog and tools/list names differ")
        self.check(
            "catalog-resource",
            "passed",
            {
                "tools": len(catalog_names),
                "sha256": hashlib.sha256(contents[0]["text"].encode()).hexdigest(),
            },
        )

        unknown = self.client.call("telegram.not_a_real_tool")
        if unknown.get("ok") is not False or (unknown.get("error") or {}).get("code") != "TOOL_NOT_FOUND":
            raise RuntimeError(f"unknown tool did not fail safely: {unknown}")
        self.check("unknown-tool-safe-error", "passed", {"code": "TOOL_NOT_FOUND"})

        invalid_cases = [
            ("telegram.system.health", {"unexpected": True}),
            ("telegram.settings.set", {"values": {"sort_files_by_name": "yes"}}),
            (
                "telegram.folder.upsert",
                {"title": "1234567890123", "idempotency_key": "schema-probe"},
            ),
            (
                "telegram.message.send_location",
                {
                    "peer": "not a peer",
                    "latitude": 90.0001,
                    "longitude": 0.25,
                    "idempotency_key": "schema-probe",
                },
            ),
            (
                "telegram.business.location_set",
                {"address": "schema probe", "latitude": "0.5"},
            ),
            (
                "telegram.file.get",
                {"file_ref": "f_" + "g" * 64},
            ),
            ("telegram.message.delete", {"peer": "saved", "message_ids": [1]}),
            ("telegram.dialog.clear_history", {"peer": "saved"}),
        ]
        for tool_name, arguments in invalid_cases:
            invalid = self.client.call(tool_name, arguments)
            if invalid.get("ok") is not False or (invalid.get("error") or {}).get("code") != "INVALID_ARGUMENT":
                raise RuntimeError(
                    f"{tool_name} malformed arguments did not fail closed: {invalid}"
                )
        self.check(
            "schema-and-confirmation-fail-closed",
            "passed",
            {"cases": len(invalid_cases)},
        )
        fractional_number = self.client.call(
            "telegram.message.send_location",
            {
                "peer": "not a peer",
                "latitude": 0.125,
                "longitude": -0.25,
                "idempotency_key": "schema-number-probe",
            },
        )
        number_error = fractional_number.get("error") or {}
        if fractional_number.get("ok") is not False or number_error.get("code") in {
            None,
            "INTERNAL_ERROR",
        }:
            raise RuntimeError(
                "fractional number schema probe did not reach an actionable domain error: "
                f"{fractional_number}"
            )
        self.check(
            "number-schema-supported",
            "passed",
            {"downstream_error": number_error.get("code")},
        )
        return tools, catalog

    def required_argument_guards(self, tools: list[dict[str, Any]]) -> None:
        """Exercise every required-input tool without allowing a side effect."""
        checked = 0
        for tool in tools:
            name = str(tool.get("name"))
            required = (tool.get("inputSchema") or {}).get("required") or []
            if not required:
                continue
            result = self.client.call(name, {})
            error = result.get("error") or {}
            if result.get("ok") is not False or error.get("code") != "INVALID_ARGUMENT":
                raise RuntimeError(
                    f"{name} did not reject missing required inputs without execution: {result}"
                )
            evidence = self.report["tool_evidence"].setdefault(
                name, {"status": "registered-schema-verified"}
            )
            evidence["missing_required_guard"] = True
            checked += 1
        self.check(
            "all-required-inputs-fail-closed",
            "passed",
            {"tools": checked},
        )

    def destructive_confirmation_guards(self, tools: list[dict[str, Any]]) -> None:
        checked: list[str] = []
        for tool in tools:
            annotations = tool.get("annotations") or {}
            if not annotations.get("destructiveHint"):
                continue
            name = str(tool.get("name"))
            schema = tool.get("inputSchema") or {}
            required = list(schema.get("required") or [])
            if "_confirm" not in required:
                raise RuntimeError(f"{name} is destructive without required _confirm")
            properties = schema.get("properties") or {}
            arguments = {
                field: schema_probe_value(properties.get(field) or {})
                for field in required
                if field != "_confirm"
            }
            result = self.client.call(name, arguments)
            error = result.get("error") or {}
            message = str(error.get("message") or "")
            if (
                result.get("ok") is not False
                or error.get("code") != "INVALID_ARGUMENT"
                or "_confirm" not in message
            ):
                raise RuntimeError(
                    f"{name} did not fail closed specifically on missing _confirm: "
                    f"{safe_tool_error_code(result)}"
                )
            evidence = self.report["tool_evidence"].setdefault(
                name, {"status": "registered-schema-verified"}
            )
            evidence["confirmation_guard"] = True
            checked.append(name)
        self.check(
            "all-destructive-confirmations-fail-closed",
            "passed",
            {"tools": len(checked), "tool_names": checked},
        )

    def broad_zero_argument_reads(
        self, tools: list[dict[str, Any]], *, logged_in: bool
    ) -> None:
        """Run all discoverable zero-argument reads and preserve conditional errors."""
        succeeded = 0
        conditional = 0
        for tool in tools:
            annotations = tool.get("annotations") or {}
            schema = tool.get("inputSchema") or {}
            if not annotations.get("readOnlyHint") or (schema.get("required") or []):
                continue
            name = str(tool.get("name"))
            existing = self.report["tool_evidence"].get(name) or {}
            if existing.get("status") == "runtime-verified":
                continue
            result = self.client.call(name, {})
            if result.get("ok") is True:
                data = result.get("data") or {}
                summary = {
                    "keys": sorted(data)[:20] if isinstance(data, dict) else [],
                }
                self.tool(name, "runtime-verified", summary)
                succeeded += 1
                continue
            error = result.get("error") or {}
            code = str(error.get("code") or "UNKNOWN")
            if code not in ZERO_ARGUMENT_CONDITIONAL_CODES:
                raise RuntimeError(
                    f"{name} zero-argument read failed unexpectedly with {code}"
                )
            if logged_in and code == "NOT_LOGGED_IN":
                raise RuntimeError(
                    f"{name} returned NOT_LOGGED_IN after account.get_me succeeded"
                )
            self.tool(
                name,
                f"runtime-blocked-{code.lower().replace('_', '-')}",
                {"code": code, "retryable": bool(error.get("retryable"))},
            )
            conditional += 1
        self.check(
            "all-zero-argument-reads",
            "passed",
            {"succeeded": succeeded, "conditional": conditional},
        )

    def safe_account_reads(self) -> bool:
        health = self.call_ok("telegram.system.health")
        self.tool("telegram.system.health", "runtime-verified", health.get("data"))
        accounts = self.call_ok("telegram.account.list")
        self.tool("telegram.account.list", "runtime-verified", accounts.get("data"))
        activated = [
            item
            for item in (accounts.get("data") or {}).get("accounts", [])
            if item.get("activated")
        ]
        if not activated:
            failure = self.client.call("telegram.account.get_me")
            code = (failure.get("error") or {}).get("code")
            if failure.get("ok") is not False or code != "NOT_LOGGED_IN":
                raise RuntimeError(
                    "pre-login account error is not actionable: "
                    f"{safe_tool_error_code(failure)}"
                )
            self.tool(
                "telegram.account.get_me",
                "runtime-verified-safe-error",
                {"code": code},
            )
            self.check(
                "account-state",
                "blocked-login",
                {"next": "Log into the beta debug app through the trusted Telegram GUI."},
            )
            return False

        me = self.call_ok("telegram.account.get_me")
        self.tool("telegram.account.get_me", "runtime-verified", me.get("data"))
        saved = self.call_ok("telegram.peer.resolve", {"peer": "saved"})
        self.tool("telegram.peer.resolve", "runtime-verified", saved.get("data"))
        dialogs = self.call_ok("telegram.dialog.list", {"limit": 20})
        dialog_items = (dialogs.get("data") or {}).get("dialogs", [])
        self.tool(
            "telegram.dialog.list",
            "runtime-verified",
            {"count": len(dialog_items)},
        )
        history = self.call_ok(
            "telegram.message.history", {"peer": "saved", "limit": 10}
        )
        history_messages = (history.get("data") or {}).get("messages", [])
        self.tool(
            "telegram.message.history",
            "runtime-verified",
            {"count": len(history_messages)},
        )
        if history_messages:
            message_id = int(history_messages[0]["message_id"])
            fetched = self.call_ok(
                "telegram.message.get",
                {"peer": "saved", "message_ids": [message_id]},
            )
            fetched_messages = (fetched.get("data") or {}).get("messages", [])
            if not any(int(item.get("message_id", 0)) == message_id for item in fetched_messages):
                raise RuntimeError("message.get did not return the requested Saved Messages item")
            self.tool(
                "telegram.message.get",
                "runtime-verified",
                {"message_id": message_id},
            )
        else:
            self.tool(
                "telegram.message.get",
                "runtime-blocked-no-message-fixture",
                {"peer": "saved"},
            )
        scheduled = self.call_ok(
            "telegram.message.scheduled_list", {"peer": "saved"}
        )
        self.tool(
            "telegram.message.scheduled_list",
            "runtime-verified",
            {"count": len((scheduled.get("data") or {}).get("messages", []))},
        )
        draft = self.call_ok("telegram.draft.get", {"peer": "saved"})
        self.tool(
            "telegram.draft.get",
            "runtime-verified",
            {"exists": bool((draft.get("data") or {}).get("exists"))},
        )
        search = self.call_ok(
            "telegram.message.search",
            {"peer": "saved", "query": "__mcp_nonexistent_probe__", "limit": 5},
        )
        self.tool(
            "telegram.message.search",
            "runtime-verified",
            {"count": len((search.get("data") or {}).get("messages", []))},
        )
        contacts = self.call_ok("telegram.contact.list", {"limit": 20})
        self.tool(
            "telegram.contact.list",
            "runtime-verified",
            {"count": len((contacts.get("data") or {}).get("contacts", []))},
        )
        contact_search = self.call_ok(
            "telegram.contact.search", {"query": "__mcp_nonexistent_probe__", "limit": 5}
        )
        self.tool(
            "telegram.contact.search",
            "runtime-verified",
            {"count": len((contact_search.get("data") or {}).get("contacts", []))},
        )
        blocked = self.call_ok("telegram.contact.blocked_list", {"limit": 20})
        self.tool(
            "telegram.contact.blocked_list",
            "runtime-verified",
            {"count": len((blocked.get("data") or {}).get("blocked", []))},
        )
        member_peer = next(
            (
                item.get("peer")
                for item in dialog_items
                if str(item.get("peer", "")).startswith(("chat:", "channel:"))
            ),
            None,
        )
        if member_peer:
            chat = self.call_ok("telegram.chat.get", {"peer": member_peer})
            self.tool(
                "telegram.chat.get",
                "runtime-verified",
                {
                    "peer": member_peer,
                    "participants_count": (chat.get("data") or {}).get("participants_count"),
                },
            )
            members = self.client.call(
                "telegram.chat.members_list", {"peer": member_peer, "limit": 5}
            )
            if members.get("ok") is True:
                self.tool(
                    "telegram.chat.members_list",
                    "runtime-verified",
                    {
                        "peer": member_peer,
                        "count": len((members.get("data") or {}).get("members", [])),
                    },
                )
            else:
                self.tool(
                    "telegram.chat.members_list",
                    "runtime-verified-safe-error",
                    {"peer": member_peer, "error": members.get("error")},
                )
        else:
            self.tool(
                "telegram.chat.get",
                "runtime-blocked-no-group-fixture",
            )
            self.tool(
                "telegram.chat.members_list",
                "runtime-blocked-no-group-fixture",
            )
        settings = self.call_ok("telegram.settings.get")
        self.tool(
            "telegram.settings.get",
            "runtime-verified",
            {"keys": sorted((settings.get("data") or {}).get("values", {}))},
        )
        sessions = self.call_ok("telegram.session.list")
        self.tool(
            "telegram.session.list",
            "runtime-verified",
            {"count": len((sessions.get("data") or {}).get("sessions", []))},
        )
        self.check("account-state", "passed", {"activated_accounts": len(activated)})
        return True

    def saved_messages_loop(self) -> None:
        marker = f"MCP-E2E-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
        key = f"send-{uuid.uuid4().hex}"
        sent = self.call_ok(
            "telegram.message.send_text",
            {"peer": "saved", "text": marker, "idempotency_key": key},
        )
        replay = self.call_ok(
            "telegram.message.send_text",
            {"peer": "saved", "text": marker, "idempotency_key": key},
        )
        if not (replay.get("data") or {}).get("idempotent_replay"):
            raise RuntimeError("same idempotency key was not replayed")
        sent_ids_raw = list((sent.get("data") or {}).get("message_ids", []))
        replay_ids_raw = list((replay.get("data") or {}).get("message_ids", []))
        if sent_ids_raw != replay_ids_raw:
            raise RuntimeError("idempotent replay returned different message_ids")
        conflict = self.client.call(
            "telegram.message.send_text",
            {
                "peer": "saved",
                "text": marker + "-DIFFERENT-PAYLOAD",
                "idempotency_key": key,
            },
        )
        if conflict.get("ok") is not False or (
            conflict.get("error") or {}
        ).get("code") != "IDEMPOTENCY_CONFLICT":
            raise RuntimeError("same key with a different payload was not rejected")
        self.check(
            "idempotency-conflict-guard",
            "passed",
            {"same_ids_on_replay": True, "different_payload_rejected": True},
        )

        integer_probes = [
            (
                "telegram.message.get",
                {"peer": "saved", "message_ids": [4294967297]},
            ),
            (
                "telegram.message.get",
                {"peer": "saved", "message_ids": [9223372036854775808]},
            ),
            (
                "telegram.message.get",
                {"peer": "saved", "message_ids": [1.5]},
            ),
            (
                "telegram.message.delete",
                {
                    "peer": "saved",
                    "message_ids": [4294967297],
                    "_confirm": True,
                },
            ),
        ]
        for tool_name, probe_args in integer_probes:
            rejected = self.client.call(tool_name, probe_args)
            if rejected.get("ok") is not False or (
                rejected.get("error") or {}
            ).get("code") != "INVALID_ARGUMENT":
                raise RuntimeError(
                    f"{tool_name} accepted a non-exact or overflowing integer"
                )
        self.check(
            "exact-integer-guards",
            "passed",
            {"probes": len(integer_probes), "all_invalid_argument": True},
        )
        message_ids = [
            int(value)
            for value in (sent.get("data") or {}).get("message_ids", [])
            if int(value) > 0
        ]
        self.cleanup_message_ids = list(message_ids)
        for _ in range(10):
            found = self.call_ok(
                "telegram.message.search",
                {"peer": "saved", "query": marker, "limit": 10},
            )
            matches = [
                item
                for item in (found.get("data") or {}).get("messages", [])
                if item.get("text") == marker and item.get("outgoing")
            ]
            if matches:
                message_ids = sorted(
                    set(message_ids + [int(item["message_id"]) for item in matches])
                )
                self.cleanup_message_ids = list(message_ids)
                break
            time.sleep(0.5)
        if not message_ids:
            raise RuntimeError("sent message could not be read back")
        message_id = message_ids[-1]
        self.tool(
            "telegram.message.send_text",
            "runtime-verified",
            {"message_id": message_id, "idempotent_replay": True},
        )

        edited_text = marker + "-EDITED"
        self.call_ok(
            "telegram.message.edit_text",
            {"peer": "saved", "message_id": message_id, "text": edited_text},
        )
        edited = self.call_ok(
            "telegram.message.search",
            {"peer": "saved", "query": edited_text, "limit": 10},
        )
        if not any(
            int(item.get("message_id", 0)) == message_id
            and item.get("text") == edited_text
            for item in (edited.get("data") or {}).get("messages", [])
        ):
            raise RuntimeError("edited message could not be read back")
        self.tool(
            "telegram.message.edit_text",
            "runtime-verified",
            {"message_id": message_id},
        )

        fetched = self.call_ok(
            "telegram.message.get",
            {"peer": "saved", "message_ids": [message_id]},
        )
        fetched_messages = (fetched.get("data") or {}).get("messages", [])
        if not any(
            int(item.get("message_id", 0)) == message_id
            and item.get("text") == edited_text
            for item in fetched_messages
        ):
            raise RuntimeError("message.get did not independently read back the edit")
        self.tool(
            "telegram.message.get",
            "runtime-verified",
            {"message_id": message_id, "readback": "edited_text"},
        )

        reaction = self.client.call(
            "telegram.message.reaction_set",
            {"peer": "saved", "message_id": message_id, "reaction": "👍"},
        )
        if reaction.get("ok") is not True:
            error = reaction.get("error") or {}
            if error.get("code") != "PREMIUM_REQUIRED":
                raise RuntimeError(
                    "reaction_set failed outside its explicit Premium boundary: "
                    f"{error.get('code') or 'UNKNOWN'}"
                )
            self.tool(
                "telegram.message.reaction_set",
                "runtime-blocked-premium-required",
                {"peer": "saved", "code": "PREMIUM_REQUIRED"},
            )
        else:
            for _ in range(10):
                reacted = self.call_ok(
                    "telegram.message.get",
                    {"peer": "saved", "message_ids": [message_id]},
                )
                reactions = next(
                    (
                        item.get("reactions", [])
                        for item in (reacted.get("data") or {}).get("messages", [])
                        if int(item.get("message_id", 0)) == message_id
                    ),
                    [],
                )
                if any(item.get("value") == "👍" and item.get("chosen") for item in reactions):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("reaction_set could not be read back as chosen")
            self.call_ok(
                "telegram.message.reaction_set",
                {"peer": "saved", "message_id": message_id, "reaction": ""},
            )
            for _ in range(10):
                unreacted = self.call_ok(
                    "telegram.message.get",
                    {"peer": "saved", "message_ids": [message_id]},
                )
                reactions = next(
                    (
                        item.get("reactions", [])
                        for item in (unreacted.get("data") or {}).get("messages", [])
                        if int(item.get("message_id", 0)) == message_id
                    ),
                    [],
                )
                if not any(item.get("value") == "👍" and item.get("chosen") for item in reactions):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("reaction_set removal could not be read back")
            self.tool(
                "telegram.message.reaction_set",
                "runtime-verified",
                {"message_id": message_id, "set_and_removed": True},
            )

        draft_text = marker + "-DRAFT"
        self.call_ok("telegram.draft.set", {"peer": "saved", "text": draft_text})
        draft = self.call_ok("telegram.draft.get", {"peer": "saved"})
        if not (draft.get("data") or {}).get("exists") or (draft.get("data") or {}).get("text") != draft_text:
            raise RuntimeError("draft.set could not be read back")
        self.call_ok("telegram.draft.clear", {"peer": "saved"})
        cleared_draft = self.call_ok("telegram.draft.get", {"peer": "saved"})
        if (cleared_draft.get("data") or {}).get("exists"):
            raise RuntimeError("draft.clear could not be read back")
        self.tool("telegram.draft.get", "runtime-verified", {"set_and_cleared": True})
        self.tool("telegram.draft.set", "runtime-verified", {"text": draft_text})
        self.tool("telegram.draft.clear", "runtime-verified", {"exists": False})

        settings = self.call_ok(
            "telegram.settings.get", {"keys": ["sort_files_by_name"]}
        )
        current = (settings.get("data") or {}).get("values", {}).get("sort_files_by_name")
        if not isinstance(current, bool):
            raise RuntimeError("settings.get returned no boolean sort_files_by_name")
        changed = not current
        try:
            self.call_ok(
                "telegram.settings.set", {"values": {"sort_files_by_name": changed}}
            )
            readback = self.call_ok(
                "telegram.settings.get", {"keys": ["sort_files_by_name"]}
            )
            if (readback.get("data") or {}).get("values", {}).get("sort_files_by_name") != changed:
                raise RuntimeError("settings mutation did not read back the changed value")
        finally:
            self.call_ok(
                "telegram.settings.set", {"values": {"sort_files_by_name": current}}
            )
        restored = self.call_ok(
            "telegram.settings.get", {"keys": ["sort_files_by_name"]}
        )
        if (restored.get("data") or {}).get("values", {}).get("sort_files_by_name") != current:
            raise RuntimeError("settings mutation did not restore the original value")
        self.tool(
            "telegram.settings.set",
            "runtime-verified",
            {
                "key": "sort_files_by_name",
                "changed_to": changed,
                "restored_to": current,
            },
        )

        self.call_ok(
            "telegram.message.delete",
            {
                "peer": "saved",
                "message_ids": message_ids,
                "for_everyone": True,
                "_confirm": True,
            },
        )
        for _ in range(10):
            remaining = self.call_ok(
                "telegram.message.search",
                {"peer": "saved", "query": marker, "limit": 10},
            )
            if not any(
                item.get("text", "").startswith(marker)
                for item in (remaining.get("data") or {}).get("messages", [])
            ):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("cleanup message remains visible after delete")
        self.assert_messages_absent("saved", message_ids)
        self.tool(
            "telegram.message.delete",
            "runtime-verified",
            {"cleaned_message_ids": message_ids},
        )
        self.cleanup_message_ids = []
        self.check(
            "saved-messages-closed-loop",
            "passed",
            {"marker": marker, "cleaned": True},
        )

    def file_and_qr_loop(self) -> None:
        marker = f"MCP-QR-{uuid.uuid4().hex}"
        refs: list[str] = []
        try:
            payload = marker.encode("utf-8")
            staged = self.call_ok(
                "telegram.file.put_base64",
                {
                    "name": "mcp-probe.txt",
                    "mime_type": "text/plain",
                    "base64": base64.b64encode(payload).decode("ascii"),
                },
            )
            plain_ref = str((staged.get("data") or {}).get("file_ref") or "")
            if not plain_ref:
                raise RuntimeError("file.put_base64 returned no file_ref")
            refs.append(plain_ref)
            self.cleanup_file_refs.append(plain_ref)
            fetched = self.call_ok("telegram.file.get", {"file_ref": plain_ref})
            if int((fetched.get("data") or {}).get("size", -1)) != len(payload):
                raise RuntimeError("file.get size does not match staged payload")
            read = self.call_ok("telegram.file.read_base64", {"file_ref": plain_ref})
            if base64.b64decode((read.get("data") or {}).get("base64", "")) != payload:
                raise RuntimeError("file.read_base64 did not return the staged bytes")
            self.tool("telegram.file.put_base64", "runtime-verified", {"bytes": len(payload)})
            self.tool("telegram.file.get", "runtime-verified", {"bytes": len(payload)})
            self.tool("telegram.file.read_base64", "runtime-verified", {"bytes": len(payload)})

            encoded = self.call_ok("telegram.qr.encode", {"text": marker, "size": 384})
            qr_ref = str((encoded.get("data") or {}).get("file_ref") or "")
            if not qr_ref:
                raise RuntimeError("qr.encode returned no file_ref")
            refs.append(qr_ref)
            self.cleanup_file_refs.append(qr_ref)
            decoded = self.call_ok("telegram.qr.decode_file", {"file_ref": qr_ref})
            if (decoded.get("data") or {}).get("text") != marker:
                raise RuntimeError("qr.decode_file did not return qr.encode content")
            self.tool("telegram.qr.encode", "runtime-verified", {"size": 384})
            self.tool(
                "telegram.qr.decode_file",
                "runtime-verified",
                {"round_trip": True},
            )
        finally:
            cleanup_failures: list[str] = []
            for reference in refs:
                result = self.client.call(
                    "telegram.file.delete",
                    {"file_ref": reference, "_confirm": True},
                )
                if result.get("ok") is True:
                    if reference in self.cleanup_file_refs:
                        self.cleanup_file_refs.remove(reference)
                else:
                    cleanup_failures.append(reference)
                    self.check(
                        "file-fixture-cleanup",
                        "failed",
                        {"file_ref": reference},
                        str(result.get("error")),
                    )
        if cleanup_failures:
            raise RuntimeError(
                f"failed to delete {len(cleanup_failures)} staged file fixture(s)"
            )
        for reference in refs:
            stale = self.client.call("telegram.file.get", {"file_ref": reference})
            if stale.get("ok") is not False or (stale.get("error") or {}).get("code") != "STALE_REFERENCE":
                raise RuntimeError("deleted staged file remained addressable")
        if refs:
            self.tool(
                "telegram.file.delete",
                "runtime-verified",
                {"deleted_refs": len(refs), "stale_readback": True},
            )
        self.check(
            "file-and-qr-closed-loop",
            "passed",
            {"files_cleaned": len(refs)},
        )

    def chunked_file_loop(self) -> None:
        total_size = 1_100_123
        chunk_size = 400 * 1024
        payload = bytes((index * 31 + 17) % 251 for index in range(total_size))
        digest = hashlib.sha256(payload).hexdigest()
        upload_ref = ""
        final_ref = ""
        try:
            begun = self.call_ok(
                "telegram.file.upload_begin",
                {
                    "name": "mcp-chunk-probe.bin",
                    "mime_type": "application/octet-stream",
                    "total_size": total_size,
                    "sha256": digest,
                },
            )
            upload_ref = str((begun.get("data") or {}).get("upload_ref") or "")
            if not upload_ref:
                raise RuntimeError("file.upload_begin returned no upload_ref")
            self.cleanup_upload_refs.append(upload_ref)
            listed = self.call_ok("telegram.file.upload_list", {"limit": 100})
            if not any(
                item.get("upload_ref") == upload_ref
                for item in (listed.get("data") or {}).get("uploads", [])
            ):
                raise RuntimeError("file.upload_list did not expose the resumable session")

            offsets = list(range(0, total_size, chunk_size))
            first = payload[offsets[0] : offsets[0] + chunk_size]
            first_args = {
                "upload_ref": upload_ref,
                "offset": 0,
                "base64": base64.b64encode(first).decode("ascii"),
                "chunk_sha256": hashlib.sha256(first).hexdigest(),
            }
            self.call_ok("telegram.file.upload_append", first_args)
            first_replay = self.call_ok("telegram.file.upload_append", first_args)
            if not (first_replay.get("data") or {}).get("idempotent_replay"):
                raise RuntimeError("identical upload chunk was not replayed")
            status = self.call_ok(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if int((status.get("data") or {}).get("received_bytes", -1)) != len(first):
                raise RuntimeError("upload status did not preserve the first chunk offset")

            conflicting = bytearray(first)
            conflicting[0] ^= 0xFF
            conflict = self.client.call(
                "telegram.file.upload_append",
                {
                    "upload_ref": upload_ref,
                    "offset": 0,
                    "base64": base64.b64encode(conflicting).decode("ascii"),
                    "chunk_sha256": hashlib.sha256(conflicting).hexdigest(),
                },
            )
            if conflict.get("ok") is not False or (
                conflict.get("error") or {}
            ).get("code") != "UPLOAD_OFFSET_CONFLICT":
                raise RuntimeError("different bytes at a committed offset were not rejected")

            for offset in offsets[1:]:
                chunk = payload[offset : offset + chunk_size]
                self.call_ok(
                    "telegram.file.upload_append",
                    {
                        "upload_ref": upload_ref,
                        "offset": offset,
                        "base64": base64.b64encode(chunk).decode("ascii"),
                        "chunk_sha256": hashlib.sha256(chunk).hexdigest(),
                    },
                )
            ready = self.call_ok(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if int((ready.get("data") or {}).get("received_bytes", -1)) != total_size:
                raise RuntimeError("upload status did not reach total_size")

            committed = self.call_ok(
                "telegram.file.upload_commit", {"upload_ref": upload_ref}
            )
            final_ref = str((committed.get("data") or {}).get("file_ref") or "")
            if not final_ref:
                raise RuntimeError("file.upload_commit returned no file_ref")
            self.cleanup_file_refs.append(final_ref)
            replay = self.call_ok(
                "telegram.file.upload_commit", {"upload_ref": upload_ref}
            )
            if not (replay.get("data") or {}).get("idempotent_replay"):
                raise RuntimeError("file.upload_commit did not replay its tombstone")
            if (replay.get("data") or {}).get("file_ref") != final_ref:
                raise RuntimeError("commit replay returned a different file_ref")

            read_digest = hashlib.sha256()
            offset = 0
            while offset < total_size:
                result = self.call_ok(
                    "telegram.file.read_base64",
                    {
                        "file_ref": final_ref,
                        "offset": offset,
                        "length": min(1024 * 1024, total_size - offset),
                    },
                )
                chunk = base64.b64decode((result.get("data") or {}).get("base64", ""))
                if not chunk:
                    raise RuntimeError("chunked read returned an empty non-EOF block")
                read_digest.update(chunk)
                offset += len(chunk)
            if offset != total_size or read_digest.hexdigest() != digest:
                raise RuntimeError("committed large file failed full chunked SHA readback")

            purged_complete = self.call_ok(
                "telegram.file.upload_cancel",
                {
                    "upload_ref": upload_ref,
                    "purge_terminal": True,
                    "_confirm": True,
                },
            )
            if not (purged_complete.get("data") or {}).get("final_file_preserved"):
                raise RuntimeError("purging a complete upload removed its final file")
            purged_status = self.client.call(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if purged_status.get("ok") is not False or (
                purged_status.get("error") or {}
            ).get("code") != "STALE_REFERENCE":
                raise RuntimeError("purged complete tombstone remained addressable")
            recovered = self.call_ok(
                "telegram.file.upload_begin",
                {
                    "name": "mcp-chunk-probe.bin",
                    "mime_type": "application/octet-stream",
                    "total_size": total_size,
                    "sha256": digest,
                },
            )
            if (recovered.get("data") or {}).get("state") != "complete":
                raise RuntimeError("pre-existing final file did not recreate a complete tombstone")
            recovered_status = self.call_ok(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if not (recovered_status.get("data") or {}).get("final_present"):
                raise RuntimeError("recreated complete tombstone was not queryable")
            recovered_commit = self.call_ok(
                "telegram.file.upload_commit", {"upload_ref": upload_ref}
            )
            if (recovered_commit.get("data") or {}).get("file_ref") != final_ref:
                raise RuntimeError("recreated complete tombstone changed file_ref")

            self.call_ok(
                "telegram.file.delete", {"file_ref": final_ref, "_confirm": True}
            )
            self.cleanup_file_refs.remove(final_ref)
            final_ref = ""
            reopened = self.call_ok(
                "telegram.file.upload_begin",
                {
                    "name": "mcp-chunk-probe.bin",
                    "mime_type": "application/octet-stream",
                    "total_size": total_size,
                    "sha256": digest,
                },
            )
            if int((reopened.get("data") or {}).get("received_bytes", -1)) != 0:
                raise RuntimeError("deleted final file did not reopen an empty upload session")
            self.call_ok(
                "telegram.file.upload_cancel",
                {"upload_ref": upload_ref, "_confirm": True},
            )
            cancelled = self.call_ok(
                "telegram.file.upload_cancel",
                {"upload_ref": upload_ref, "_confirm": True},
            )
            if not (cancelled.get("data") or {}).get("idempotent_replay"):
                raise RuntimeError("upload cancellation tombstone did not replay")
            cancelled_status = self.call_ok(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if (cancelled_status.get("data") or {}).get("state") != "cancelled":
                raise RuntimeError("upload status did not expose cancelled tombstone")
            blocked_reopen = self.call_ok(
                "telegram.file.upload_begin",
                {
                    "name": "mcp-chunk-probe.bin",
                    "mime_type": "application/octet-stream",
                    "total_size": total_size,
                    "sha256": digest,
                },
            )
            if (
                (blocked_reopen.get("data") or {}).get("state") != "cancelled"
                or not (blocked_reopen.get("data") or {}).get(
                    "reopen_cancelled_required"
                )
            ):
                raise RuntimeError("ordinary upload_begin resurrected a cancelled session")
            explicit_reopen = self.call_ok(
                "telegram.file.upload_begin",
                {
                    "name": "mcp-chunk-probe.bin",
                    "mime_type": "application/octet-stream",
                    "total_size": total_size,
                    "sha256": digest,
                    "reopen_cancelled": True,
                },
            )
            if (
                (explicit_reopen.get("data") or {}).get("state") != "active"
                or not (explicit_reopen.get("data") or {}).get(
                    "reopened_cancelled"
                )
            ):
                raise RuntimeError("explicit reopen_cancelled did not create an active session")
            self.call_ok(
                "telegram.file.upload_cancel",
                {"upload_ref": upload_ref, "_confirm": True},
            )
            self.call_ok(
                "telegram.file.upload_cancel",
                {
                    "upload_ref": upload_ref,
                    "purge_terminal": True,
                    "_confirm": True,
                },
            )
            stale = self.client.call(
                "telegram.file.upload_status", {"upload_ref": upload_ref}
            )
            if stale.get("ok") is not False or (
                stale.get("error") or {}
            ).get("code") != "STALE_REFERENCE":
                raise RuntimeError("purged upload tombstone remained addressable")
            self.cleanup_upload_refs.remove(upload_ref)

            for tool_name in (
                "telegram.file.upload_list",
                "telegram.file.upload_begin",
                "telegram.file.upload_status",
                "telegram.file.upload_append",
                "telegram.file.upload_commit",
                "telegram.file.upload_cancel",
            ):
                self.tool(
                    tool_name,
                    "runtime-verified",
                    {"bytes": total_size, "chunks": len(offsets)},
                )
            self.check(
                "chunked-file-closed-loop",
                "passed",
                {
                    "bytes": total_size,
                    "chunks": len(offsets),
                    "non_aligned_tail": total_size % chunk_size,
                    "commit_replayed": True,
                    "complete_tombstone_recovered": True,
                    "cancelled_reopen_guarded": True,
                    "terminal_purged": True,
                },
            )
        finally:
            if final_ref:
                result = self.client.call(
                    "telegram.file.delete",
                    {"file_ref": final_ref, "_confirm": True},
                )
                if result.get("ok") is True and final_ref in self.cleanup_file_refs:
                    self.cleanup_file_refs.remove(final_ref)
            if upload_ref and upload_ref in self.cleanup_upload_refs:
                result = self.client.call(
                    "telegram.file.upload_cancel",
                    {
                        "upload_ref": upload_ref,
                        "purge_terminal": True,
                        "_confirm": True,
                    },
                )
                if result.get("ok") is True or (
                    result.get("error") or {}
                ).get("code") == "STALE_REFERENCE":
                    self.cleanup_upload_refs.remove(upload_ref)

    def settings_and_local_state_loop(self) -> None:
        auto = self.call_ok("telegram.settings.auto_download_get")
        networks = (auto.get("data") or {}).get("networks") or {}
        current_preset = (networks.get("wifi") or {}).get("preset")
        presets = ["off", "low", "medium", "high"]
        if current_preset in presets:
            target_preset = next(value for value in presets if value != current_preset)
            try:
                self.call_ok(
                    "telegram.settings.auto_download_set",
                    {"network": "wifi", "preset": target_preset},
                )
                changed = self.call_ok("telegram.settings.auto_download_get")
                actual = (
                    ((changed.get("data") or {}).get("networks") or {})
                    .get("wifi", {})
                    .get("preset")
                )
                if actual != target_preset:
                    raise RuntimeError("auto-download mutation did not read back")
            finally:
                self.call_ok(
                    "telegram.settings.auto_download_set",
                    {"network": "wifi", "preset": current_preset},
                )
            restored = self.call_ok("telegram.settings.auto_download_get")
            if (
                (((restored.get("data") or {}).get("networks") or {}).get("wifi") or {}).get("preset")
                != current_preset
            ):
                raise RuntimeError("auto-download preset was not restored")
            self.tool(
                "telegram.settings.auto_download_set",
                "runtime-verified",
                {"network": "wifi", "restored": True},
            )
        else:
            self.tool(
                "telegram.settings.auto_download_set",
                "runtime-blocked-custom-state-not-losslessly-restorable",
                {"network": "wifi", "preset": current_preset},
            )

        proxy_before = self.call_ok("telegram.proxy.list")
        before_data = proxy_before.get("data") or {}
        previous_id = str(before_data.get("current_proxy_id") or "")
        previous_enabled = bool(before_data.get("enabled"))
        previous_for_calls = bool(before_data.get("for_calls"))
        fake_id = ""
        try:
            created = self.call_ok(
                "telegram.proxy.upsert",
                {
                    "type": "socks5",
                    "address": f"mcp-{uuid.uuid4().hex[:12]}.invalid",
                    "port": 9,
                },
            )
            fake_id = str(
                (((created.get("data") or {}).get("proxy") or {}).get("proxy_id"))
                or ""
            )
            if not fake_id:
                raise RuntimeError("proxy.upsert returned no proxy_id")
            self.call_ok(
                "telegram.proxy.select",
                {"proxy_id": fake_id, "enabled": False},
            )
            selected = self.call_ok("telegram.proxy.list")
            selected_data = selected.get("data") or {}
            if selected_data.get("current_proxy_id") != fake_id or selected_data.get("enabled"):
                raise RuntimeError("disabled proxy selection did not read back exactly")
            self.tool("telegram.proxy.upsert", "runtime-verified", {"created": True})
            self.tool("telegram.proxy.select", "runtime-verified", {"enabled": False})
        finally:
            if fake_id:
                deleted = self.client.call(
                    "telegram.proxy.delete",
                    {"proxy_id": fake_id, "_confirm": True},
                )
                if deleted.get("ok") is not True:
                    raise RuntimeError(
                        "proxy fixture cleanup failed with "
                        f"{safe_tool_error_code(deleted)}"
                    )
            if previous_id:
                self.call_ok(
                    "telegram.proxy.select",
                    {
                        "proxy_id": previous_id,
                        "enabled": previous_enabled,
                        "for_calls": previous_for_calls,
                    },
                )
        proxy_after = self.call_ok("telegram.proxy.list")
        after_data = proxy_after.get("data") or {}
        if any(
            item.get("proxy_id") == fake_id
            for item in (after_data.get("proxies") or [])
        ):
            raise RuntimeError("deleted proxy remained in proxy.list")
        self.tool(
            "telegram.proxy.delete",
            "runtime-verified",
            {"deleted": True, "previous_selection_restored": bool(previous_id)},
        )

        cache_payload = b"mcp-staging-cache-clear-closed-loop"
        cache_digest = hashlib.sha256(cache_payload).hexdigest()
        cache_upload = self.call_ok(
            "telegram.file.upload_begin",
            {
                "name": "mcp-cache-clear-probe.bin",
                "mime_type": "application/octet-stream",
                "total_size": len(cache_payload),
                "sha256": cache_digest,
            },
        )
        cache_upload_ref = str(
            (cache_upload.get("data") or {}).get("upload_ref") or ""
        )
        if not cache_upload_ref:
            raise RuntimeError("cache-clear upload fixture returned no upload_ref")
        self.cleanup_upload_refs.append(cache_upload_ref)
        self.call_ok(
            "telegram.file.upload_append",
            {
                "upload_ref": cache_upload_ref,
                "offset": 0,
                "base64": base64.b64encode(cache_payload).decode("ascii"),
                "chunk_sha256": cache_digest,
            },
        )

        storage = self.call_ok(
            "telegram.storage.cache_clear",
            {"categories": ["mcp_staging"], "_confirm": True},
        )
        if not isinstance((storage.get("data") or {}).get("after"), dict):
            raise RuntimeError("storage.cache_clear returned no independent after scan")
        stale_upload = self.client.call(
            "telegram.file.upload_status", {"upload_ref": cache_upload_ref}
        )
        if stale_upload.get("ok") is not False or (
            stale_upload.get("error") or {}
        ).get("code") != "STALE_REFERENCE":
            raise RuntimeError("storage.cache_clear left an upload-session ghost")
        self.cleanup_upload_refs.remove(cache_upload_ref)
        remaining_uploads = self.call_ok(
            "telegram.file.upload_list", {"limit": 100, "offset": 0}
        )
        if int((remaining_uploads.get("data") or {}).get("total_count", -1)) != 0:
            raise RuntimeError("storage.cache_clear left upload sessions in the catalog")
        remaining_files = self.call_ok("telegram.file.list", {"limit": 100})
        if int((remaining_files.get("data") or {}).get("count", -1)) != 0:
            raise RuntimeError("storage.cache_clear left staged file references")
        self.tool(
            "telegram.storage.cache_clear",
            "runtime-verified",
            {
                "categories": ["mcp_staging"],
                "upload_session_readback": "STALE_REFERENCE",
                "staged_file_count": 0,
                "upload_session_count": 0,
            },
        )

        usage = self.call_ok("telegram.network.usage")
        self.tool("telegram.network.usage", "runtime-verified", {"read_before_reset": True})
        reset = self.call_ok(
            "telegram.network.usage_reset",
            {"network": "mobile", "_confirm": True},
        )
        reset_total = (
            ((((reset.get("data") or {}).get("after") or {}).get("categories") or {}).get("total"))
            or {}
        )
        zero_readback = (
            str(reset_total.get("sent_bytes")) == "0"
            and str(reset_total.get("received_bytes")) == "0"
        )
        if not zero_readback:
            raise RuntimeError("network.usage_reset returned non-zero total counters")
        self.tool(
            "telegram.network.usage_reset",
            "runtime-verified",
            {"network": "mobile", "zero_readback": zero_readback},
        )
        self.check(
            "settings-and-local-state-closed-loop",
            "passed",
            {"proxy_cleaned": True, "storage_scanned": True},
        )

    def notification_and_privacy_loop(self) -> None:
        global_before = self.call_ok(
            "telegram.notification.global_get", {"domain": "private"}
        )
        global_value = bool((global_before.get("data") or {}).get("show_previews"))
        try:
            self.call_ok(
                "telegram.notification.global_set",
                {"domain": "private", "show_previews": not global_value},
            )
            changed = self.call_ok(
                "telegram.notification.global_get", {"domain": "private"}
            )
            if bool((changed.get("data") or {}).get("show_previews")) == global_value:
                raise RuntimeError("global notification value did not change")
        finally:
            self.call_ok(
                "telegram.notification.global_set",
                {"domain": "private", "show_previews": global_value},
            )
        self.tool("telegram.notification.global_get", "runtime-verified", {"domain": "private"})
        self.tool("telegram.notification.global_set", "runtime-verified", {"restored": True})

        reaction_before = self.call_ok("telegram.notification.reactions_get")
        reaction_value = bool((reaction_before.get("data") or {}).get("show_previews"))
        try:
            self.call_ok(
                "telegram.notification.reactions_set",
                {"show_previews": not reaction_value},
            )
            changed = self.call_ok("telegram.notification.reactions_get")
            if bool((changed.get("data") or {}).get("show_previews")) == reaction_value:
                raise RuntimeError("reaction notification value did not change")
        finally:
            self.call_ok(
                "telegram.notification.reactions_set",
                {"show_previews": reaction_value},
            )
        self.tool("telegram.notification.reactions_get", "runtime-verified", {"readback": True})
        self.tool("telegram.notification.reactions_set", "runtime-verified", {"restored": True})

        peer_before = self.client.call("telegram.notification.peer_get", {"peer": "saved"})
        if peer_before.get("ok") is True:
            peer_value = bool((peer_before.get("data") or {}).get("show_previews"))
            try:
                self.call_ok(
                    "telegram.notification.peer_set",
                    {"peer": "saved", "show_previews": not peer_value},
                )
            finally:
                self.call_ok(
                    "telegram.notification.peer_set",
                    {"peer": "saved", "show_previews": peer_value},
                )
            self.tool("telegram.notification.peer_get", "runtime-verified", {"peer": "saved"})
            self.tool("telegram.notification.peer_set", "runtime-verified", {"restored": True})
        else:
            code = (peer_before.get("error") or {}).get("code")
            self.tool(
                "telegram.notification.peer_get",
                "runtime-blocked-saved-peer-policy",
                {"code": code},
            )
            self.tool(
                "telegram.notification.peer_set",
                "runtime-blocked-saved-peer-policy",
                {"code": code},
            )

        privacy_before = self.call_ok("telegram.privacy.get", {"key": "about"})
        original = privacy_before.get("data") or {}
        original_base = str(original.get("base") or "")
        if original_base not in {"everybody", "contacts", "nobody"}:
            raise RuntimeError(f"privacy.get returned unsupported base: {original_base}")

        def privacy_arguments(base_value: str) -> dict[str, Any]:
            return {
                "key": "about",
                "base": base_value,
                "allow_peers": original.get("allow_peers") or [],
                "disallow_peers": original.get("disallow_peers") or [],
                "allow_close_friends": bool(original.get("allow_close_friends")),
                "allow_premium": bool(original.get("allow_premium")),
                "disallow_contacts": bool(original.get("disallow_contacts")),
                "bots": original.get("bots") or "inherit",
                "replace": True,
            }

        alternate = "contacts" if original_base != "contacts" else "everybody"
        try:
            self.call_ok("telegram.privacy.set", privacy_arguments(alternate))
            changed = self.call_ok("telegram.privacy.get", {"key": "about"})
            if (changed.get("data") or {}).get("base") != alternate:
                raise RuntimeError("privacy mutation did not read back")
        finally:
            self.call_ok("telegram.privacy.set", privacy_arguments(original_base))
        restored = self.call_ok("telegram.privacy.get", {"key": "about"})
        if (restored.get("data") or {}).get("base") != original_base:
            raise RuntimeError("privacy base was not restored")
        self.tool("telegram.privacy.get", "runtime-verified", {"key": "about"})
        self.tool("telegram.privacy.set", "runtime-verified", {"restored": True})
        self.check(
            "notification-and-privacy-closed-loop",
            "passed",
            {"global": True, "reactions": True, "privacy": "about"},
        )

    def owned_supergroup_history_loop(self) -> None:
        suffix = uuid.uuid4().hex[:10]
        created = self.call_ok(
            "telegram.chat.create_channel",
            {
                "title": f"MCP History {suffix}",
                "about": "Disposable history-visibility acceptance fixture",
                "kind": "supergroup",
                "idempotency_key": f"history-group-{uuid.uuid4().hex}",
            },
        )
        chat = (created.get("data") or {}).get("chat") or {}
        peer = str(chat.get("peer") or "")
        if peer:
            self.cleanup_chat_peer = peer
        if not peer or chat.get("forum") or not chat.get("megagroup"):
            raise RuntimeError(
                "supergroup creation returned an incompatible chat shape: "
                f"keys={sorted(chat)}"
            )
        try:
            before = self.call_ok("telegram.chat.get", {"peer": peer})
            before_visible = bool((before.get("data") or {}).get("history_visible"))
            self.call_ok(
                "telegram.chat.history_visible_set",
                {"peer": peer, "visible": not before_visible},
            )
            changed = self.call_ok("telegram.chat.get", {"peer": peer})
            if bool((changed.get("data") or {}).get("history_visible")) == before_visible:
                raise RuntimeError("history_visible_set did not change supergroup state")
            self.tool(
                "telegram.chat.history_visible_set",
                "runtime-verified",
                {"peer": peer, "visible": not before_visible},
            )
        finally:
            if self.cleanup_chat_peer == peer:
                self.call_ok(
                    "telegram.chat.delete_owned", {"peer": peer, "_confirm": True}
                )
        self.assert_chat_absent(peer)
        self.cleanup_chat_peer = ""
        self.check(
            "owned-supergroup-history-closed-loop",
            "passed",
            {"peer": peer, "fixture_cleaned": True},
        )

    def owned_forum_workflow_loop(self) -> None:
        suffix = uuid.uuid4().hex[:10]
        title = f"MCP Forum {suffix}"
        about = f"Disposable MCP acceptance fixture {suffix}"
        forum_key = f"forum-{uuid.uuid4().hex}"
        created = self.call_ok(
            "telegram.chat.create_channel",
            {
                "title": title,
                "about": about,
                "kind": "forum",
                "idempotency_key": forum_key,
            },
        )
        chat = ((created.get("data") or {}).get("chat") or {})
        peer = str(chat.get("peer") or "")
        if peer:
            self.cleanup_chat_peer = peer
        if not peer or not chat.get("forum") or not chat.get("creator"):
            raise RuntimeError(
                "forum creation returned no owned forum peer: "
                f"keys={sorted(chat)}"
            )
        self.tool(
            "telegram.chat.create_channel",
            "runtime-verified",
            {"kind": "forum", "peer": peer, "creator": True},
        )

        replay = self.call_ok(
            "telegram.chat.create_channel",
            {
                "title": title,
                "about": about,
                "kind": "forum",
                "idempotency_key": forum_key,
            },
        )
        replay_data = replay.get("data") or {}
        replay_peer = ((replay_data.get("chat") or {}).get("peer"))
        if not replay_data.get("idempotent_replay") or replay_peer != peer:
            raise RuntimeError("forum creation idempotency did not replay the same chat")

        full = self.call_ok("telegram.chat.get", {"peer": peer})
        full_data = full.get("data") or {}
        if not full_data.get("forum") or full_data.get("about") != about:
            raise RuntimeError("chat.get did not independently read back forum creation")
        self.tool("telegram.chat.get", "runtime-verified", {"peer": peer, "forum": True})

        updated_title = title + " Updated"
        updated_about = about + " updated"
        self.call_ok("telegram.chat.update_title", {"peer": peer, "title": updated_title})
        self.call_ok("telegram.chat.update_about", {"peer": peer, "about": updated_about})
        updated = self.call_ok("telegram.chat.get", {"peer": peer})
        updated_data = updated.get("data") or {}
        if updated_data.get("title") != updated_title or updated_data.get("about") != updated_about:
            raise RuntimeError("chat title/about updates did not read back")
        self.tool("telegram.chat.update_title", "runtime-verified", {"peer": peer})
        self.tool("telegram.chat.update_about", "runtime-verified", {"peer": peer})

        members = self.call_ok("telegram.chat.members_list", {"peer": peer, "limit": 50})
        if not (members.get("data") or {}).get("members"):
            raise RuntimeError("owned forum members_list did not include its creator")
        self.tool("telegram.chat.members_list", "runtime-verified", {"peer": peer})
        member = self.call_ok(
            "telegram.chat.member_get", {"peer": peer, "member": "saved"}
        )
        if not (member.get("data") or {}).get("member"):
            raise RuntimeError("owned forum member_get did not return its creator")
        self.tool("telegram.chat.member_get", "runtime-verified", {"peer": peer})

        permissions = self.call_ok("telegram.chat.permissions_get", {"peer": peer})
        allowed = (((permissions.get("data") or {}).get("default_permissions") or {}).get("allowed") or {})
        if not isinstance(allowed.get("send_polls"), bool):
            raise RuntimeError("permissions_get returned no send_polls boolean")
        target_polls = not bool(allowed["send_polls"])
        self.call_ok(
            "telegram.chat.permissions_set",
            {"peer": peer, "allowed": {"send_polls": target_polls}, "_confirm": True},
        )
        permissions_after = self.call_ok("telegram.chat.permissions_get", {"peer": peer})
        actual_polls = (
            (((permissions_after.get("data") or {}).get("default_permissions") or {}).get("allowed") or {})
            .get("send_polls")
        )
        if actual_polls != target_polls:
            raise RuntimeError("permissions_set did not read back send_polls")
        self.tool("telegram.chat.permissions_get", "runtime-verified", {"peer": peer})
        self.tool("telegram.chat.permissions_set", "runtime-verified", {"peer": peer})

        self.call_ok("telegram.chat.slow_mode_set", {"peer": peer, "seconds": 10})
        self.call_ok("telegram.chat.slow_mode_set", {"peer": peer, "seconds": 0})
        self.call_ok("telegram.chat.auto_delete_set", {"peer": peer, "seconds": 86400})
        self.call_ok("telegram.chat.auto_delete_set", {"peer": peer, "seconds": 0})
        self.call_ok("telegram.chat.reactions_get", {"peer": peer})
        self.call_ok("telegram.chat.reactions_set", {"peer": peer, "mode": "all"})
        self.tool("telegram.chat.slow_mode_set", "runtime-verified", {"set_and_cleared": True})
        self.tool("telegram.chat.auto_delete_set", "runtime-verified", {"set_and_cleared": True})
        self.tool("telegram.chat.reactions_get", "runtime-verified", {"peer": peer})
        self.tool("telegram.chat.reactions_set", "runtime-verified", {"mode": "all"})

        invite = self.call_ok(
            "telegram.chat.invite_create",
            {
                "peer": peer,
                "title": f"MCP-{suffix}",
                "usage_limit": 3,
                "idempotency_key": f"invite-{uuid.uuid4().hex}",
            },
        )
        link = str((((invite.get("data") or {}).get("invite") or {}).get("link")) or "")
        if not link:
            raise RuntimeError("invite_create returned no link")
        active_invites = self.call_ok("telegram.chat.invite_list", {"peer": peer})
        if not any(item.get("link") == link for item in (active_invites.get("data") or {}).get("invites", [])):
            raise RuntimeError("invite_list did not read back the created link")
        self.call_ok(
            "telegram.chat.invite_revoke",
            {"peer": peer, "link": link, "_confirm": True},
        )
        revoked_invites = self.call_ok(
            "telegram.chat.invite_list", {"peer": peer, "revoked": True}
        )
        if not any(
            item.get("link") == link and item.get("revoked")
            for item in (revoked_invites.get("data") or {}).get("invites", [])
        ):
            raise RuntimeError("invite revoke was not visible in revoked invite list")
        self.tool("telegram.chat.invite_create", "runtime-verified", {"peer": peer})
        self.tool("telegram.chat.invite_list", "runtime-verified", {"active_and_revoked": True})
        self.tool("telegram.chat.invite_revoke", "runtime-verified", {"peer": peer})

        topic_title = f"Topic {suffix}"
        topic_created = self.call_ok(
            "telegram.topic.create",
            {
                "peer": peer,
                "title": topic_title,
                "idempotency_key": f"topic-{uuid.uuid4().hex}",
            },
        )
        topic = ((topic_created.get("data") or {}).get("topic") or {})
        topic_id = int(topic.get("topic_id") or 0)
        if topic_id <= 1:
            raise RuntimeError(
                "topic.create returned invalid topic shape: "
                f"keys={sorted(topic)}"
            )
        listed_topics = self.call_ok("telegram.topic.list", {"peer": peer, "limit": 50})
        if not any(int(item.get("topic_id", 0)) == topic_id for item in (listed_topics.get("data") or {}).get("topics", [])):
            raise RuntimeError("topic.list did not include the created topic")
        self.call_ok(
            "telegram.topic.update",
            {"peer": peer, "topic_id": topic_id, "title": topic_title + " Updated"},
        )
        self.call_ok("telegram.topic.pin", {"peer": peer, "topic_id": topic_id})
        self.call_ok("telegram.topic.unpin", {"peer": peer, "topic_id": topic_id})
        topic_after = self.call_ok(
            "telegram.topic.get", {"peer": peer, "topic_id": topic_id}
        )
        topic_after_data = (topic_after.get("data") or {}).get("topic") or {}
        if topic_after_data.get("title") != topic_title + " Updated" or topic_after_data.get("pinned"):
            raise RuntimeError("topic update/pin/unpin did not reach the expected final state")
        for name in [
            "telegram.topic.create",
            "telegram.topic.list",
            "telegram.topic.get",
            "telegram.topic.update",
            "telegram.topic.pin",
            "telegram.topic.unpin",
        ]:
            self.tool(name, "runtime-verified", {"peer": peer, "topic_id": topic_id})

        marker = f"MCP-TOPIC-{suffix}-{uuid.uuid4().hex[:8]}"
        sent = self.call_ok(
            "telegram.message.send_text",
            {
                "peer": peer,
                "topic_id": topic_id,
                "text": marker,
                "idempotency_key": f"topic-message-{uuid.uuid4().hex}",
            },
        )
        message_ids = [int(value) for value in (sent.get("data") or {}).get("message_ids", [])]
        if len(message_ids) != 1:
            raise RuntimeError("topic message send returned no stable message ID")
        message_id = message_ids[0]
        fetched = self.call_ok(
            "telegram.message.get", {"peer": peer, "message_ids": [message_id]}
        )
        fetched_item = next(
            (
                item
                for item in (fetched.get("data") or {}).get("messages", [])
                if int(item.get("message_id", 0)) == message_id
            ),
            {},
        )
        if fetched_item.get("text") != marker or int(fetched_item.get("topic_id", 0)) != topic_id:
            raise RuntimeError("message.get did not preserve explicit forum topic routing")
        history = self.call_ok(
            "telegram.message.history", {"peer": peer, "topic_id": topic_id, "limit": 50}
        )
        if not any(item.get("text") == marker for item in (history.get("data") or {}).get("messages", [])):
            raise RuntimeError("topic-scoped message.history did not return the sent message")
        searched = self.call_ok(
            "telegram.message.search",
            {"peer": peer, "topic_id": topic_id, "query": marker, "limit": 20},
        )
        if not any(item.get("text") == marker for item in (searched.get("data") or {}).get("messages", [])):
            raise RuntimeError("topic-scoped message.search did not return the sent message")
        self.call_ok("telegram.message.pin", {"peer": peer, "message_id": message_id})
        self.call_ok("telegram.message.unpin", {"peer": peer, "message_id": message_id})
        for name in [
            "telegram.message.send_text",
            "telegram.message.get",
            "telegram.message.history",
            "telegram.message.search",
            "telegram.message.pin",
            "telegram.message.unpin",
        ]:
            self.tool(name, "runtime-verified", {"peer": peer, "topic_id": topic_id})

        dialog = self.call_ok("telegram.dialog.get", {"peer": peer})
        if (dialog.get("data") or {}).get("peer") != peer:
            raise RuntimeError("dialog.get returned a different peer")
        self.call_ok("telegram.dialog.archive", {"peer": peer})
        if int((self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("folder_id", -1)) != 1:
            raise RuntimeError("dialog.archive did not move the forum to archive")
        self.call_ok("telegram.dialog.unarchive", {"peer": peer})
        if int((self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("folder_id", -1)) != 0:
            raise RuntimeError("dialog.unarchive did not restore the forum")
        self.call_ok("telegram.dialog.mute", {"peer": peer})
        if not (self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("muted"):
            raise RuntimeError("dialog.mute did not read back")
        self.call_ok("telegram.dialog.unmute", {"peer": peer})
        if (self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("muted"):
            raise RuntimeError("dialog.unmute did not read back")
        self.call_ok("telegram.dialog.pin", {"peer": peer})
        if not (self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("pinned"):
            raise RuntimeError("dialog.pin did not read back")
        self.call_ok("telegram.dialog.unpin", {"peer": peer})
        if (self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("pinned"):
            raise RuntimeError("dialog.unpin did not read back")
        self.call_ok("telegram.message.mark_unread", {"peer": peer})
        if not (self.call_ok("telegram.dialog.get", {"peer": peer}).get("data") or {}).get("unread_mark"):
            raise RuntimeError("message.mark_unread did not read back")
        self.call_ok(
            "telegram.message.mark_read",
            {"peer": peer, "topic_id": topic_id, "max_message_id": message_id},
        )
        for name in [
            "telegram.dialog.get",
            "telegram.dialog.archive",
            "telegram.dialog.unarchive",
            "telegram.dialog.mute",
            "telegram.dialog.unmute",
            "telegram.dialog.pin",
            "telegram.dialog.unpin",
            "telegram.message.mark_unread",
            "telegram.message.mark_read",
        ]:
            self.tool(name, "runtime-verified", {"peer": peer})

        self.call_ok(
            "telegram.message.reaction_set",
            {"peer": peer, "message_id": message_id, "reaction": "👍"},
        )
        reacted = self.call_ok(
            "telegram.message.get", {"peer": peer, "message_ids": [message_id]}
        )
        reacted_messages = (reacted.get("data") or {}).get("messages", [])
        if not reacted_messages or "👍" not in json.dumps(reacted_messages, ensure_ascii=False):
            raise RuntimeError("message.reaction_set did not read back the selected reaction")
        self.call_ok(
            "telegram.message.reaction_set",
            {"peer": peer, "message_id": message_id, "reaction": ""},
        )
        self.tool("telegram.message.reaction_set", "runtime-verified", {"peer": peer})

        structured = [
            (
                "telegram.message.send_contact",
                {
                    "peer": peer,
                    "topic_id": topic_id,
                    "phone_number": "+15555550123",
                    "first_name": "MCP",
                    "last_name": "Fixture",
                    "idempotency_key": f"contact-{uuid.uuid4().hex}",
                },
            ),
            (
                "telegram.message.send_location",
                {
                    "peer": peer,
                    "topic_id": topic_id,
                    "latitude": 0.123456,
                    "longitude": 0.654321,
                    "title": "MCP fixture",
                    "address": "Disposable test location",
                    "idempotency_key": f"location-{uuid.uuid4().hex}",
                },
            ),
            (
                "telegram.message.send_dice",
                {
                    "peer": peer,
                    "topic_id": topic_id,
                    "emoji": "🎲",
                    "idempotency_key": f"dice-{uuid.uuid4().hex}",
                },
            ),
        ]
        for name, arguments in structured:
            result = self.call_ok(name, arguments)
            if int((result.get("data") or {}).get("message_id") or 0) <= 0:
                raise RuntimeError(f"{name} returned no exact message ID")
            self.tool(name, "runtime-verified", {"peer": peer, "topic_id": topic_id})

        poll = self.call_ok(
            "telegram.message.send_poll",
            {
                "peer": peer,
                "topic_id": topic_id,
                "question": f"MCP poll {suffix}",
                "answers": ["A", "B"],
                "anonymous": False,
                "idempotency_key": f"poll-{uuid.uuid4().hex}",
            },
        )
        poll_id = int((poll.get("data") or {}).get("message_id") or 0)
        if poll_id <= 0:
            raise RuntimeError("send_poll returned no exact message ID")
        self.call_ok(
            "telegram.message.poll_vote",
            {"peer": peer, "message_id": poll_id, "answer_indices": [0]},
        )
        self.call_ok(
            "telegram.message.poll_close",
            {"peer": peer, "message_id": poll_id, "_confirm": True},
        )
        for name in [
            "telegram.message.send_poll",
            "telegram.message.poll_vote",
            "telegram.message.poll_close",
        ]:
            self.tool(name, "runtime-verified", {"peer": peer, "message_id": poll_id})

        staged_refs: list[str] = []
        try:
            document_bytes = (marker + "-DOCUMENT\n").encode()
            staged = self.call_ok(
                "telegram.file.put_base64",
                {
                    "name": "mcp-fixture.txt",
                    "mime_type": "text/plain",
                    "base64": base64.b64encode(document_bytes).decode(),
                },
            )
            staged_ref = str((staged.get("data") or {}).get("file_ref") or "")
            if not staged_ref:
                raise RuntimeError("file.put_base64 returned no document file_ref")
            staged_refs.append(staged_ref)
            caption = marker + "-CAPTION"
            media = self.call_ok(
                "telegram.message.send_media",
                {
                    "peer": peer,
                    "topic_id": topic_id,
                    "file_refs": [staged_ref],
                    "kind": "document",
                    "caption": caption,
                    "idempotency_key": f"media-{uuid.uuid4().hex}",
                },
            )
            media_ids = [
                int(value) for value in (media.get("data") or {}).get("message_ids", [])
            ]
            if len(media_ids) != 1:
                raise RuntimeError("send_media returned no exact document message ID")
            media_id = media_ids[0]
            edited_caption = caption + "-EDITED"
            self.call_ok(
                "telegram.message.edit_caption",
                {"peer": peer, "message_id": media_id, "caption": edited_caption},
            )
            media_search = self.call_ok(
                "telegram.message.media_search",
                {
                    "peer": peer,
                    "topic_id": topic_id,
                    "filter": "documents",
                    "query": edited_caption,
                    "limit": 20,
                },
            )
            if not any(
                int(item.get("message_id", 0)) == media_id
                for item in (media_search.get("data") or {}).get("messages", [])
            ):
                raise RuntimeError("media_search did not return the sent document")
            downloaded = self.call_ok(
                "telegram.file.download_message",
                {"peer": peer, "message_id": media_id},
            )
            downloaded_ref = str((downloaded.get("data") or {}).get("file_ref") or "")
            if not downloaded_ref:
                raise RuntimeError("file.download_message returned no file_ref")
            staged_refs.append(downloaded_ref)
            downloaded_bytes = base64.b64decode(
                (self.call_ok(
                    "telegram.file.read_base64", {"file_ref": downloaded_ref}
                ).get("data") or {}).get("base64", "")
            )
            if downloaded_bytes != document_bytes:
                raise RuntimeError("downloaded document bytes differ from staged bytes")
            for name in [
                "telegram.message.send_media",
                "telegram.message.edit_caption",
                "telegram.message.media_search",
                "telegram.file.download_message",
            ]:
                self.tool(name, "runtime-verified", {"peer": peer, "message_id": media_id})
        finally:
            for file_ref in list(dict.fromkeys(reversed(staged_refs))):
                self.call_ok(
                    "telegram.file.delete", {"file_ref": file_ref, "_confirm": True}
                )

        forwarded = self.call_ok(
            "telegram.message.forward",
            {
                "from_peer": peer,
                "to_peer": "saved",
                "message_ids": [message_id],
                "idempotency_key": f"forward-{uuid.uuid4().hex}",
            },
        )
        forwarded_ids = [
            int(value) for value in (forwarded.get("data") or {}).get("message_ids", [])
        ]
        if not forwarded_ids:
            raise RuntimeError("message.forward returned no Saved Messages IDs")
        self.cleanup_message_ids.extend(forwarded_ids)
        self.call_ok(
            "telegram.message.delete",
            {
                "peer": "saved",
                "message_ids": forwarded_ids,
                "for_everyone": True,
                "_confirm": True,
            },
        )
        self.assert_messages_absent("saved", forwarded_ids)
        self.cleanup_message_ids = [
            value for value in self.cleanup_message_ids if value not in forwarded_ids
        ]
        self.tool("telegram.message.forward", "runtime-verified", {"count": len(forwarded_ids)})

        draft_text = marker + "-DRAFT"
        self.call_ok(
            "telegram.draft.set",
            {"peer": peer, "topic_id": topic_id, "text": draft_text},
        )
        topic_draft = self.call_ok(
            "telegram.draft.get", {"peer": peer, "topic_id": topic_id}
        )
        if (topic_draft.get("data") or {}).get("text") != draft_text:
            raise RuntimeError("topic draft did not read back")
        self.call_ok("telegram.draft.clear", {"peer": peer, "topic_id": topic_id})
        if (self.call_ok("telegram.draft.get", {"peer": peer, "topic_id": topic_id}).get("data") or {}).get("exists"):
            raise RuntimeError("topic draft remained after clear")
        self.tool("telegram.draft.set", "runtime-verified", {"topic_id": topic_id})
        self.tool("telegram.draft.get", "runtime-verified", {"topic_id": topic_id})
        self.tool("telegram.draft.clear", "runtime-verified", {"topic_id": topic_id})

        topic_notify = self.call_ok(
            "telegram.notification.peer_get", {"peer": peer, "topic_id": topic_id}
        )
        preview_before = bool((topic_notify.get("data") or {}).get("show_previews"))
        self.call_ok(
            "telegram.notification.peer_set",
            {"peer": peer, "topic_id": topic_id, "show_previews": not preview_before},
        )
        preview_changed = self.call_ok(
            "telegram.notification.peer_get", {"peer": peer, "topic_id": topic_id}
        )
        if bool((preview_changed.get("data") or {}).get("show_previews")) == preview_before:
            raise RuntimeError("topic notification setting did not change")
        self.tool("telegram.notification.peer_get", "runtime-verified", {"topic_id": topic_id})
        self.tool("telegram.notification.peer_set", "runtime-verified", {"topic_id": topic_id})

        folders_before = self.call_ok("telegram.folder.list")
        before_ids = [
            int(item["folder_id"])
            for item in (folders_before.get("data") or {}).get("folders", [])
            if not item.get("default")
        ]
        folder = self.call_ok(
            "telegram.folder.upsert",
            {
                "title": f"MCP {suffix[:8]}",
                "include_peers": [peer],
                "idempotency_key": f"folder-{uuid.uuid4().hex}",
            },
        )
        folder_id = int((folder.get("data") or {}).get("folder_id") or 0)
        if folder_id < 2:
            raise RuntimeError("folder.upsert returned no custom folder ID")
        self.cleanup_folder_ids.append(folder_id)
        folder_read = self.call_ok("telegram.folder.get", {"folder_id": folder_id})
        if peer not in ((folder_read.get("data") or {}).get("include_peers") or []):
            raise RuntimeError("folder.get did not preserve explicit forum inclusion")
        target_order = [folder_id] + before_ids
        self.call_ok("telegram.folder.reorder", {"folder_ids": target_order})
        reordered = self.call_ok("telegram.folder.list")
        reordered_ids = [
            int(item["folder_id"])
            for item in (reordered.get("data") or {}).get("folders", [])
            if not item.get("default")
        ]
        if reordered_ids != target_order:
            raise RuntimeError("folder.reorder did not return the requested complete order")
        for name in [
            "telegram.folder.list",
            "telegram.folder.get",
            "telegram.folder.upsert",
            "telegram.folder.reorder",
        ]:
            self.tool(name, "runtime-verified", {"folder_id": folder_id})

        admin_log = self.call_ok("telegram.chat.admin_log", {"peer": peer, "limit": 50})
        self.tool(
            "telegram.chat.admin_log",
            "runtime-verified",
            {"events": len((admin_log.get("data") or {}).get("events", []))},
        )
        boost = self.call_ok("telegram.chat.boost_status", {"peer": peer})
        self.tool("telegram.chat.boost_status", "runtime-verified", {"peer": peer})
        del boost

        self.call_ok(
            "telegram.topic.delete",
            {"peer": peer, "topic_id": topic_id, "_confirm": True},
        )
        topic_missing = self.client.call(
            "telegram.topic.get", {"peer": peer, "topic_id": topic_id}
        )
        if topic_missing.get("ok") is not False or (topic_missing.get("error") or {}).get("code") != "TOPIC_NOT_FOUND":
            raise RuntimeError("deleted topic remained addressable")
        self.tool("telegram.topic.delete", "runtime-verified", {"topic_id": topic_id})

        self.call_ok(
            "telegram.folder.delete", {"folder_id": folder_id, "_confirm": True}
        )
        missing_folder = self.client.call("telegram.folder.get", {"folder_id": folder_id})
        if missing_folder.get("ok") is not False or (missing_folder.get("error") or {}).get("code") != "FOLDER_NOT_FOUND":
            raise RuntimeError("deleted folder remained addressable")
        self.cleanup_folder_ids.remove(folder_id)
        self.tool("telegram.folder.delete", "runtime-verified", {"folder_id": folder_id})

        self.call_ok(
            "telegram.dialog.clear_history",
            {"peer": peer, "for_everyone": True, "_confirm": True},
        )
        cleared = self.call_ok(
            "telegram.message.history", {"peer": peer, "limit": 1}
        )
        if (cleared.get("data") or {}).get("messages"):
            raise RuntimeError("dialog.clear_history left message history")
        self.tool("telegram.dialog.clear_history", "runtime-verified", {"peer": peer})

        self.call_ok("telegram.chat.delete_owned", {"peer": peer, "_confirm": True})
        self.assert_chat_absent(peer)
        self.cleanup_chat_peer = ""
        self.tool("telegram.chat.delete_owned", "runtime-verified", {"peer": peer})
        self.check(
            "owned-forum-closed-loop",
            "passed",
            {"peer": peer, "topic_id": topic_id, "fixtures_cleaned": True},
        )

    def assert_messages_absent(self, peer: str, message_ids: list[int]) -> None:
        result = self.client.call(
            "telegram.message.get", {"peer": peer, "message_ids": message_ids}
        )
        if result.get("ok") is False:
            code = str((result.get("error") or {}).get("code") or "")
            if code in {"MESSAGE_NOT_FOUND", "NOT_FOUND"}:
                return
            raise RuntimeError(
                f"message absence readback failed unexpectedly with {code or 'UNKNOWN'}"
            )
        returned_ids = {
            int(item.get("message_id", 0))
            for item in (result.get("data") or {}).get("messages", [])
            if int(item.get("message_id", 0)) > 0
        }
        remaining = sorted(returned_ids.intersection(message_ids))
        if remaining:
            raise RuntimeError(f"deleted messages remained addressable: {remaining}")

    def assert_chat_absent(self, peer: str) -> None:
        result = self.client.call("telegram.chat.get", {"peer": peer})
        if result.get("ok") is not False:
            raise RuntimeError("deleted chat remained addressable")
        error = result.get("error") or {}
        code = str(error.get("code") or "")
        telegram_error = str((error.get("details") or {}).get("telegram_error") or "")
        if code in {"CHAT_NOT_FOUND", "PEER_NOT_FOUND"}:
            return
        if code == "TELEGRAM_ERROR" and telegram_error in {
            "CHANNEL_INVALID",
            "CHANNEL_PRIVATE",
            "CHAT_ID_INVALID",
            "PEER_ID_INVALID",
        }:
            return
        raise RuntimeError(
            "chat absence readback failed unexpectedly with "
            f"{code or 'UNKNOWN'}:{telegram_error or 'UNKNOWN'}"
        )

    def best_effort_cleanup(self) -> bool:
        clean = True
        for file_ref in list(self.cleanup_file_refs):
            try:
                self.call_ok(
                    "telegram.file.delete",
                    {"file_ref": file_ref, "_confirm": True},
                )
                self.cleanup_file_refs.remove(file_ref)
                self.check("failure-file-cleanup", "passed", {"file_ref": file_ref})
            except Exception as error:
                clean = False
                self.check(
                    "failure-file-cleanup",
                    "failed",
                    {"file_ref": file_ref},
                    str(error),
                )
        for upload_ref in list(self.cleanup_upload_refs):
            try:
                result = self.client.call(
                    "telegram.file.upload_cancel",
                    {
                        "upload_ref": upload_ref,
                        "purge_terminal": True,
                        "_confirm": True,
                    },
                )
                if result.get("ok") is not True and (
                    result.get("error") or {}
                ).get("code") != "STALE_REFERENCE":
                    raise RuntimeError(
                        "upload cleanup failed with " + safe_tool_error_code(result)
                    )
                self.cleanup_upload_refs.remove(upload_ref)
                self.check(
                    "failure-upload-cleanup", "passed", {"upload_ref": upload_ref}
                )
            except Exception as error:
                clean = False
                self.check(
                    "failure-upload-cleanup",
                    "failed",
                    {"upload_ref": upload_ref},
                    str(error),
                )
        if self.cleanup_message_ids:
            ids = list(self.cleanup_message_ids)
            try:
                self.call_ok(
                    "telegram.message.delete",
                    {
                        "peer": "saved",
                        "message_ids": ids,
                        "for_everyone": True,
                        "_confirm": True,
                    },
                )
                self.assert_messages_absent("saved", ids)
                self.cleanup_message_ids = []
                self.check("failure-message-cleanup", "passed", {"message_ids": ids})
            except Exception as error:
                clean = False
                self.check(
                    "failure-message-cleanup",
                    "failed",
                    {"message_ids": ids},
                    str(error),
                )
        for folder_id in list(reversed(self.cleanup_folder_ids)):
            try:
                self.call_ok(
                    "telegram.folder.delete", {"folder_id": folder_id, "_confirm": True}
                )
                missing = self.client.call(
                    "telegram.folder.get", {"folder_id": folder_id}
                )
                if (
                    missing.get("ok") is not False
                    or (missing.get("error") or {}).get("code") != "FOLDER_NOT_FOUND"
                ):
                    raise RuntimeError("deleted cleanup folder remained addressable")
                self.cleanup_folder_ids.remove(folder_id)
                self.check("failure-folder-cleanup", "passed", {"folder_id": folder_id})
            except Exception as error:
                clean = False
                self.check(
                    "failure-folder-cleanup",
                    "failed",
                    {"folder_id": folder_id},
                    str(error),
                )
        if self.cleanup_chat_peer:
            peer = self.cleanup_chat_peer
            try:
                self.call_ok(
                    "telegram.chat.delete_owned", {"peer": peer, "_confirm": True}
                )
                self.assert_chat_absent(peer)
                self.cleanup_chat_peer = ""
                self.check("failure-chat-cleanup", "passed", {"peer": peer})
            except Exception as error:
                clean = False
                self.check(
                    "failure-chat-cleanup",
                    "failed",
                    {"peer": peer},
                    str(error),
                )
        return clean

    def finish(self) -> dict[str, Any]:
        self.report["finished_at"] = now_iso()
        statuses = [item["status"] for item in self.report["checks"]]
        self.report["summary"] = {
            "checks": len(statuses),
            "passed": sum(value == "passed" for value in statuses),
            "blocked_login": sum(value == "blocked-login" for value in statuses),
            "failed": sum(value == "failed" for value in statuses),
            "runtime_tool_evidence": len(self.report["tool_evidence"]),
        }
        self.report = redact_report_value(self.report)
        self.report_path.parent.mkdir(parents=True, exist_ok=True)
        self.report_path.write_text(
            json.dumps(self.report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return self.report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--url", default=os.environ.get("TELEGRAM_MCP_URL", "http://127.0.0.1:19876/mcp")
    )
    parser.add_argument("--token-env", default="TELEGRAM_MCP_TOKEN")
    parser.add_argument("--write-saved-messages", action="store_true")
    parser.add_argument(
        "--report",
        type=Path,
        default=Path(".mcp-work/telegram-mcp-20260801/runtime-validation.json"),
    )
    return parser.parse_args()


def run_acceptance(
    *,
    url: str,
    token: str,
    report_path: Path,
    write_saved_messages: bool,
) -> tuple[int, dict[str, Any]]:
    acceptance = Acceptance(RpcClient(url, token), report_path)
    exit_code = 0
    try:
        acceptance.client.wait_until_ready()
        tools, _ = acceptance.protocol(token)
        acceptance.required_argument_guards(tools)
        acceptance.destructive_confirmation_guards(tools)
        logged_in = acceptance.safe_account_reads()
        acceptance.broad_zero_argument_reads(tools, logged_in=logged_in)
        if write_saved_messages:
            # These closed loops only touch app-private staging state and are
            # deliberately independent of Telegram account authorization.  Run
            # them before the login gate so a logged-out beta build still gets
            # real business evidence for the local file/QR/upload tools.
            acceptance.file_and_qr_loop()
            acceptance.chunked_file_loop()
            if not logged_in:
                raise RuntimeError("--write-saved-messages requires a logged-in test account")
            acceptance.settings_and_local_state_loop()
            acceptance.notification_and_privacy_loop()
            acceptance.saved_messages_loop()
            acceptance.owned_supergroup_history_loop()
            acceptance.owned_forum_workflow_loop()
    except Exception as error:
        acceptance.check("runtime", "failed", error=str(error))
        exit_code = 1
    finally:
        if write_saved_messages:
            cleanup_ok = acceptance.best_effort_cleanup()
            residuals = {
                "file_refs": len(acceptance.cleanup_file_refs),
                "upload_refs": len(acceptance.cleanup_upload_refs),
                "message_fixtures": len(acceptance.cleanup_message_ids),
                "folder_ids": len(acceptance.cleanup_folder_ids),
                "chat_peer": bool(acceptance.cleanup_chat_peer),
            }
            cleanup_ok = cleanup_ok and not any(residuals.values())
            acceptance.check(
                "cleanup-final",
                "passed" if cleanup_ok else "failed",
                residuals,
                None if cleanup_ok else "one or more acceptance fixtures remain",
            )
            if not cleanup_ok:
                exit_code = 1
    report = acceptance.finish()
    return exit_code, report


def main() -> int:
    args = parse_args()
    token = os.environ.get(args.token_env, "").strip()
    if not token:
        print(f"Missing MCP token in {args.token_env}", file=sys.stderr)
        return 2
    exit_code, report = run_acceptance(
        url=args.url,
        token=token,
        report_path=args.report,
        write_saved_messages=args.write_saved_messages,
    )
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    print(args.report.resolve())
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

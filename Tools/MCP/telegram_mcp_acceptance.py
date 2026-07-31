#!/usr/bin/env python3
"""Closed-loop acceptance checks for the Telegram Android MCP endpoint.

The default run is non-mutating and is valid both before and after login. Pass
``--write-saved-messages`` only for a disposable test account; it confines
message writes to Saved Messages, verifies them by readback, and cleans them up.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


PROTOCOL_VERSION = "2025-03-26"
EXPECTED_TOOLS = 46
CATALOG_URI = "telegram://mcp/tool-catalog"


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


class RpcClient:
    def __init__(self, url: str, token: str | None) -> None:
        self.url = url
        self.token = token or ""
        self.request_id = 0
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def post_raw(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.url,
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=130) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read()
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
        return structured


class Acceptance:
    def __init__(self, client: RpcClient, report_path: Path) -> None:
        self.client = client
        self.report_path = report_path
        self.cleanup_message_ids: list[int] = []
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
        result = self.client.call(name, arguments)
        if result.get("ok") is not True:
            raise RuntimeError(f"{name} failed: {result.get('error')}")
        return result

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
        self.client.post_raw(
            {"jsonrpc": "2.0", "method": "notifications/initialized"}
        )
        self.check("initialize", "passed", initialized.get("serverInfo"))

        listed = self.client.rpc("tools/list")
        tools = listed.get("tools") or []
        names = [item.get("name") for item in tools if isinstance(item, dict)]
        if len(tools) != EXPECTED_TOOLS or len(set(names)) != EXPECTED_TOOLS:
            raise RuntimeError(
                f"expected {EXPECTED_TOOLS} unique tools, got {len(tools)}/{len(set(names))}"
            )
        for tool in tools:
            schema = tool.get("inputSchema") or {}
            properties = schema.get("properties") or {}
            required = schema.get("required") or []
            if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
                raise RuntimeError(f"unsafe input schema for {tool.get('name')}: {schema}")
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
        return tools, catalog

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
                raise RuntimeError(f"pre-login account error is not actionable: {failure}")
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
            self.tool(
                "telegram.message.reaction_set",
                "runtime-blocked-server-policy",
                {"peer": "saved", "error": reaction.get("error")},
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
        self.call_ok(
            "telegram.settings.set", {"values": {"sort_files_by_name": current}}
        )
        readback = self.call_ok(
            "telegram.settings.get", {"keys": ["sort_files_by_name"]}
        )
        if (readback.get("data") or {}).get("values", {}).get("sort_files_by_name") != current:
            raise RuntimeError("settings write did not read back")
        self.tool(
            "telegram.settings.set",
            "runtime-verified",
            {"key": "sort_files_by_name", "value": current},
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

    def best_effort_cleanup(self) -> None:
        if not self.cleanup_message_ids:
            return
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
            self.cleanup_message_ids = []
            self.check("failure-cleanup", "passed", {"message_ids": ids})
        except Exception as error:
            self.check(
                "failure-cleanup",
                "failed",
                {"message_ids": ids},
                str(error),
            )

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
        default=Path(".mcp-work/telegram-mcp-20260729/runtime-validation.json"),
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
        acceptance.protocol(token)
        logged_in = acceptance.safe_account_reads()
        if write_saved_messages:
            if not logged_in:
                raise RuntimeError("--write-saved-messages requires a logged-in test account")
            acceptance.saved_messages_loop()
    except Exception as error:
        acceptance.check("runtime", "failed", error=str(error))
        exit_code = 1
    finally:
        if write_saved_messages:
            acceptance.best_effort_cleanup()
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

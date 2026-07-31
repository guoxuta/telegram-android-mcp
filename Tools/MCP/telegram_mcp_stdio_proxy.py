#!/usr/bin/env python3
"""Bridge a stdio MCP client to Telegram's on-emulator HTTP MCP endpoint.

The Android build script creates the host-to-guest port mapping. This proxy is
then suitable for clients that only know how to launch stdio MCP servers.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

from telegram_mcp_adb_bridge import (
    DEFAULT_ADB_HOST,
    DEFAULT_ADB_PORT,
    AdbMcpHttpBridge,
)


DEFAULT_URL = "http://127.0.0.1:19876/mcp"
DEFAULT_TOKEN = ""
DEFAULT_PROTOCOL_VERSION = "2025-03-26"


class McpHttpBridge:
    def __init__(self, url: str, token: str) -> None:
        self.url = url
        self.token = token
        self.protocol_version = DEFAULT_PROTOCOL_VERSION
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def get_json(self, url: str) -> dict[str, Any]:
        request = urllib.request.Request(
            url,
            method="GET",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        with self.opener.open(request, timeout=15) as response:
            return json.loads(response.read().decode("utf-8"))

    def post(self, message: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
        data = json.dumps(message, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=data,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json, text/event-stream",
                "Content-Type": "application/json; charset=utf-8",
                "MCP-Protocol-Version": self.protocol_version,
            },
        )
        try:
            with self.opener.open(request, timeout=310) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
        payload = json.loads(raw.decode("utf-8")) if raw else None
        if (
            message.get("method") == "initialize"
            and payload
            and isinstance(payload.get("result"), dict)
        ):
            negotiated = payload["result"].get("protocolVersion")
            if isinstance(negotiated, str):
                self.protocol_version = negotiated
        return status, payload


def rpc_request(request_id: int, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    message: dict[str, Any] = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
    }
    if params is not None:
        message["params"] = params
    return message


def run_smoke(bridge: McpHttpBridge, expected_tools: int) -> int:
    health_url = bridge.url.removesuffix("/mcp") + "/health"
    health = bridge.get_json(health_url)
    status, initialized = bridge.post(
        rpc_request(
            1,
            "initialize",
            {
                "protocolVersion": DEFAULT_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "telegram-android-smoke", "version": "1.0.0"},
            },
        )
    )
    if status != 200 or not initialized or "result" not in initialized:
        raise RuntimeError(f"initialize failed ({status}): {initialized}")
    bridge.post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    status, listed = bridge.post(rpc_request(2, "tools/list"))
    if status != 200 or not listed or "result" not in listed:
        raise RuntimeError(f"tools/list failed ({status}): {listed}")
    tools = listed["result"].get("tools", [])

    status, resource = bridge.post(
        rpc_request(
            3,
            "resources/read",
            {"uri": "telegram://mcp/tool-catalog"},
        )
    )
    if status != 200 or not resource or "result" not in resource:
        raise RuntimeError(f"resources/read failed ({status}): {resource}")
    contents = resource["result"].get("contents", [])
    inventory = json.loads(contents[0]["text"])
    actual_tools = len(tools)
    if actual_tools != expected_tools:
        raise RuntimeError(f"expected {expected_tools} tools, got {actual_tools}")
    catalog_tools = inventory.get("tools") or []
    if len(catalog_tools) != expected_tools:
        raise RuntimeError(
            f"catalog resource expected {expected_tools} tools, got {len(catalog_tools)}"
        )
    names = [item.get("name") for item in tools if isinstance(item, dict)]
    if len(names) != len(set(names)):
        raise RuntimeError("tools/list returned duplicate names")

    print(
        json.dumps(
            {
                "ok": True,
                "endpoint": bridge.url,
                "health": health,
                "protocolVersion": initialized["result"]["protocolVersion"],
                "tools": actual_tools,
                "catalog": {
                    "schemaVersion": inventory.get("schema_version"),
                    "sourceRevision": inventory.get("source_revision"),
                    "tools": len(catalog_tools),
                },
                "firstTool": tools[0]["name"],
                "lastTool": tools[-1]["name"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def run_stdio(bridge: McpHttpBridge) -> int:
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        request_id: Any = None
        try:
            message = json.loads(line)
            if not isinstance(message, dict):
                raise ValueError("Each stdio message must be one JSON object")
            request_id = message.get("id")
            status, response = bridge.post(message)
            if response is not None:
                sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                sys.stdout.flush()
            elif status != 202 and request_id is not None:
                raise RuntimeError(f"HTTP {status} returned no JSON-RPC response")
        except Exception as error:  # Keep the stdio bridge alive after one bad call.
            print(f"Telegram MCP bridge error: {error}", file=sys.stderr, flush=True)
            if request_id is not None:
                failure = {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32603, "message": str(error)},
                }
                sys.stdout.write(json.dumps(failure, separators=(",", ":")) + "\n")
                sys.stdout.flush()
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("TELEGRAM_MCP_URL", DEFAULT_URL))
    parser.add_argument(
        "--token",
        default=os.environ.get("TELEGRAM_MCP_TOKEN", DEFAULT_TOKEN),
    )
    parser.add_argument("--smoke", action="store_true", help="Verify the installed server and exit")
    parser.add_argument("--expected-tools", type=int, default=46)
    parser.add_argument(
        "--adb-direct",
        action="store_true",
        help="Tunnel MCP directly through the emulator ADB socket instead of host port forwarding",
    )
    parser.add_argument("--adb-host", default=DEFAULT_ADB_HOST)
    parser.add_argument("--adb-port", type=int, default=DEFAULT_ADB_PORT)
    parser.add_argument("--adb-key", default=os.environ.get("TELEGRAM_ADB_KEY"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.token:
        raise RuntimeError(
            "Telegram MCP token is missing; set TELEGRAM_MCP_TOKEN or pass --token"
        )
    bridge = (
        AdbMcpHttpBridge(
            args.url,
            args.token,
            protocol_version=DEFAULT_PROTOCOL_VERSION,
            adb_host=args.adb_host,
            adb_port=args.adb_port,
            adb_key=args.adb_key,
        )
        if args.adb_direct
        else McpHttpBridge(args.url, args.token)
    )
    if args.smoke:
        return run_smoke(bridge, args.expected_tools)
    return run_stdio(bridge)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as error:
        print(f"Telegram MCP smoke failed: {error}", file=sys.stderr)
        raise SystemExit(1)

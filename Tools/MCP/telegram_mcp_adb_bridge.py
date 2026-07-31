#!/usr/bin/env python3
"""HTTP-shaped MCP transport tunneled directly over an emulator ADB socket.

This avoids relying on ``adb forward`` or the emulator console ``redir``
command, both of which can be unreliable with some Windows platform-tools
releases.  The public class intentionally matches the small interface used by
``McpHttpBridge`` in the stdio proxy.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


DEFAULT_ADB_HOST = "127.0.0.1"
DEFAULT_ADB_PORT = 5555


def _load_adb_shell() -> tuple[Any, Any, Any, Any]:
    """Load adb-shell, preferring the repository-local pinned installation."""

    configured = os.environ.get("TELEGRAM_PYTHON_ADB_ROOT")
    repository_root = Path(__file__).resolve().parents[2]
    candidates = [
        Path(configured) if configured else None,
        repository_root / ".toolchains" / "python-adb-shell",
    ]
    for candidate in candidates:
        if candidate and (candidate / "adb_shell" / "adb_device.py").is_file():
            value = str(candidate)
            if value not in sys.path:
                sys.path.insert(0, value)
            break

    try:
        from adb_shell import constants
        from adb_shell.adb_device import AdbDeviceTcp
        from adb_shell.adb_message import AdbMessage
        from adb_shell.auth.sign_pythonrsa import PythonRSASigner
    except ImportError as error:
        raise RuntimeError(
            "adb-shell is unavailable; run telegram-source-to-emulator.ps1 "
            "once or set TELEGRAM_PYTHON_ADB_ROOT"
        ) from error
    return AdbDeviceTcp, AdbMessage, PythonRSASigner, constants


def resolve_adb_key(explicit: str | None = None) -> Path:
    candidates: list[Path] = []
    for value in (
        explicit,
        os.environ.get("TELEGRAM_ADB_KEY"),
        str(Path(os.environ["ANDROID_USER_HOME"]) / "adbkey")
        if os.environ.get("ANDROID_USER_HOME")
        else None,
        str(Path.home() / ".android" / "adbkey"),
    ):
        if value:
            candidates.append(Path(value).expanduser())
    for candidate in candidates:
        if candidate.is_file() and Path(f"{candidate}.pub").is_file():
            return candidate
    checked = ", ".join(str(candidate) for candidate in candidates) or "(none)"
    raise RuntimeError(
        "No usable emulator ADB key pair was found. Pass --adb-key or set "
        f"TELEGRAM_ADB_KEY. Checked: {checked}"
    )


class AdbMcpHttpBridge:
    """Send each HTTP request through ADB's ``tcp:<port>`` device service."""

    def __init__(
        self,
        url: str,
        token: str,
        *,
        protocol_version: str,
        adb_host: str = DEFAULT_ADB_HOST,
        adb_port: int = DEFAULT_ADB_PORT,
        adb_key: str | None = None,
    ) -> None:
        parsed = urlsplit(url)
        if parsed.scheme != "http" or not parsed.hostname:
            raise ValueError("Direct ADB MCP transport requires an http:// URL")
        self.url = url
        self.token = token
        self.protocol_version = protocol_version
        self.adb_host = adb_host
        self.adb_port = adb_port
        self.guest_port = parsed.port or 80
        self.mcp_path = parsed.path or "/mcp"
        self._key_path = resolve_adb_key(adb_key)
        self._device_type, self._message_type, signer_type, self._constants = (
            _load_adb_shell()
        )
        private = self._key_path.read_text(encoding="utf-8")
        public = Path(f"{self._key_path}.pub").read_text(encoding="utf-8")
        self._signer = signer_type(public, private)

    def get_json(self, url: str) -> dict[str, Any]:
        parsed = urlsplit(url)
        status, body = self._request("GET", parsed.path or "/")
        if status < 200 or status >= 300:
            raise RuntimeError(f"MCP HTTP {status}: {body.decode('utf-8', 'replace')}")
        return json.loads(body.decode("utf-8"))

    def post(self, message: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
        data = json.dumps(message, separators=(",", ":")).encode("utf-8")
        status, body = self._request("POST", self.mcp_path, data)
        payload = json.loads(body.decode("utf-8")) if body else None
        if (
            message.get("method") == "initialize"
            and payload
            and isinstance(payload.get("result"), dict)
        ):
            negotiated = payload["result"].get("protocolVersion")
            if isinstance(negotiated, str):
                self.protocol_version = negotiated
        return status, payload

    def _connect(self) -> Any:
        device = self._device_type(
            self.adb_host,
            self.adb_port,
            default_transport_timeout_s=120,
        )
        device.connect(
            rsa_keys=[self._signer],
            auth_timeout_s=30,
            read_timeout_s=30,
        )
        return device

    def _request(
        self,
        method: str,
        path: str,
        data: bytes | None = None,
    ) -> tuple[int, bytes]:
        headers = {
            "Host": f"127.0.0.1:{self.guest_port}",
            "Connection": "close",
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": self.protocol_version,
        }
        if data is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["Content-Length"] = str(len(data))
        request = f"{method} {path} HTTP/1.1\r\n".encode("ascii")
        request += b"".join(
            f"{name}: {value}\r\n".encode("utf-8")
            for name, value in headers.items()
        )
        request += b"\r\n" + (data or b"")

        device = self._connect()
        try:
            transaction = device._open(  # pylint: disable=protected-access
                f"tcp:{self.guest_port}".encode("ascii"),
                120,
                310,
                310,
            )
            # Keep writes comfortably below the negotiated ADB packet size.
            packet_size = min(256 * 1024, max(4096, int(device._maxdata) - 24))
            for offset in range(0, len(request), packet_size):
                packet = request[offset : offset + packet_size]
                adb_message = self._message_type(
                    self._constants.WRTE,
                    transaction.local_id,
                    transaction.remote_id,
                    packet,
                )
                device._io_manager.send(  # pylint: disable=protected-access
                    adb_message,
                    transaction,
                )
                device._read_until(  # pylint: disable=protected-access
                    [self._constants.OKAY],
                    transaction,
                )
            raw = b"".join(
                device._read_until_close(transaction)  # pylint: disable=protected-access
            )
        finally:
            device.close()
        return self._parse_http_response(raw)

    @classmethod
    def _parse_http_response(cls, raw: bytes) -> tuple[int, bytes]:
        marker = raw.find(b"\r\n\r\n")
        if marker < 0:
            raise RuntimeError(f"Malformed MCP HTTP response: {raw[:200]!r}")
        header_bytes, body = raw[:marker], raw[marker + 4 :]
        lines = header_bytes.split(b"\r\n")
        try:
            status = int(lines[0].split()[1])
        except (IndexError, ValueError) as error:
            raise RuntimeError(f"Malformed MCP status line: {lines[0]!r}") from error
        headers: dict[bytes, bytes] = {}
        for line in lines[1:]:
            if b":" in line:
                name, value = line.split(b":", 1)
                headers[name.strip().lower()] = value.strip().lower()
        if headers.get(b"transfer-encoding") == b"chunked":
            body = cls._decode_chunked(body)
        elif b"content-length" in headers:
            body = body[: int(headers[b"content-length"])]
        return status, body

    @staticmethod
    def _decode_chunked(body: bytes) -> bytes:
        decoded = bytearray()
        position = 0
        while True:
            line_end = body.find(b"\r\n", position)
            if line_end < 0:
                raise RuntimeError("Malformed chunked MCP response")
            size_text = body[position:line_end].split(b";", 1)[0]
            try:
                size = int(size_text, 16)
            except ValueError as error:
                raise RuntimeError("Malformed chunk size in MCP response") from error
            position = line_end + 2
            if size == 0:
                return bytes(decoded)
            chunk_end = position + size
            if chunk_end + 2 > len(body) or body[chunk_end : chunk_end + 2] != b"\r\n":
                raise RuntimeError("Truncated chunked MCP response")
            decoded.extend(body[position:chunk_end])
            position = chunk_end + 2

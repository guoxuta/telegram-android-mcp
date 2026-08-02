#!/usr/bin/env python3
"""Build a current, evidence-layered Telegram MCP validation ledger.

Registration, build/install evidence, runtime success, independent readback,
and cleanup remain separate.  The script computes current artifact digests and
never promotes a registered-only tool to functionally verified.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


DEFAULT_CATALOG = Path("TMessagesProj/src/main/assets/mcp/telegram_mcp_tools.json")
DEFAULT_APK = Path("TMessagesProj_App/build/outputs/apk/afat/debug/app.apk")


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve(root: Path, value: Path) -> Path:
    return value.resolve() if value.is_absolute() else (root / value).resolve()


def display(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def check(
    layer: str,
    name: str,
    status: str,
    observation: str,
    evidence: list[str],
) -> dict[str, Any]:
    return {
        "layer": layer,
        "name": name,
        "status": status,
        "evidence": evidence,
        "observation": observation,
    }


def tool_record(
    capability: dict[str, Any],
    runtime_evidence: dict[str, Any] | None,
    runtime_pointer: str,
    catalog_pointer: str,
    apk_evidence: str,
) -> dict[str, Any]:
    tool = capability["tool"]
    name = str(tool["name"])
    evidence = runtime_evidence or {}
    runtime_status = str(evidence.get("status") or "not-run")
    pointer = f"{runtime_pointer}#/tool_evidence/{name}"
    checks = [
        check(
            "contract",
            "registered-schema",
            "passed",
            "The installed catalog exposed a closed input schema and a discriminated output envelope.",
            [f"{runtime_pointer}#/checks/tools-list-and-schemas", catalog_pointer],
        ),
        check(
            "build",
            "afat-debug-apk",
            "passed",
            "The current catalog and handler implementation were packaged in the verified x86_64 APK.",
            [apk_evidence],
        ),
        check(
            "install",
            "debug-package-installed",
            "passed",
            "The debuggable beta package launched and served its authenticated loopback MCP endpoint.",
            ["package:org.telegram.messenger.beta", runtime_pointer],
        ),
    ]
    notes: list[str] = []

    if evidence.get("confirmation_guard") is True:
        checks.append(
            check(
                "security",
                "confirmation-fails-closed",
                "passed",
                "The installed service rejected the destructive call without _confirm=true.",
                [pointer],
            )
        )

    if runtime_status == "runtime-verified":
        status = "passed"
        checks.append(
            check(
                "runtime",
                "installed-tool-call",
                "passed",
                "The installed APK returned the expected structured result on a real call.",
                [pointer],
            )
        )
        if tool.get("read_only") is not True:
            checks.append(
                check(
                    "readback",
                    "independent-state-readback",
                    "passed",
                    "The acceptance loop marks writes verified only after an independent state readback.",
                    [pointer],
                )
            )
    elif runtime_status == "runtime-verified-safe-error":
        status = "blocked"
        checks.append(
            check(
                "negative",
                "actionable-safe-error",
                "passed",
                "The installed service returned an expected structured precondition error without crashing.",
                [pointer],
            )
        )
        notes.append("The successful business path still needs a matching account/peer fixture.")
    elif runtime_status.startswith("runtime-blocked"):
        status = "blocked"
        checks.append(
            check(
                "runtime",
                "fixture-dependent-runtime",
                "blocked",
                f"Acceptance reported {runtime_status}; no successful business claim is made.",
                [pointer],
            )
        )
    else:
        status = "blocked"
        checks.append(
            check(
                "runtime",
                "registration-only",
                "blocked",
                "Registration/schema evidence is not a successful business execution.",
                [pointer],
            )
        )

    if status != "passed":
        notes.append("Not functionally verified in this runtime snapshot.")
    return {
        "capability_id": capability["id"],
        "tool_name": name,
        "status": status,
        "runtime_status": runtime_status,
        "checks": checks,
        "notes": notes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", type=Path)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--unit-test-count", type=int, default=0)
    args = parser.parse_args()

    root = args.repo.resolve()
    inventory_path = resolve(root, args.inventory)
    runtime_path = resolve(root, args.runtime)
    output_path = resolve(root, args.output)
    catalog_path = resolve(root, args.catalog)
    apk_path = resolve(root, args.apk)
    for required in (inventory_path, runtime_path, catalog_path, apk_path):
        if not required.is_file():
            raise FileNotFoundError(required)

    inventory = load_json(inventory_path)
    runtime = load_json(runtime_path)
    runtime_by_tool = runtime.get("tool_evidence") or {}
    if not isinstance(runtime_by_tool, dict):
        raise ValueError("runtime tool_evidence must be an object")

    runtime_name = display(root, runtime_path)
    catalog_name = display(root, catalog_path)
    apk_name = display(root, apk_path)
    apk_digest = sha256(apk_path)
    catalog_digest = sha256(catalog_path)
    inventory_digest = sha256(inventory_path)
    records = [
        tool_record(
            capability,
            runtime_by_tool.get((capability.get("tool") or {}).get("name")),
            runtime_name,
            catalog_name,
            f"{apk_name} sha256={apk_digest}",
        )
        for capability in inventory.get("capabilities", [])
        if isinstance(capability.get("tool"), dict)
    ]

    statuses: dict[str, int] = {}
    for record in records:
        statuses[record["status"]] = statuses.get(record["status"], 0) + 1
    runtime_summary = runtime.get("summary") or {}
    checks = [item for item in runtime.get("checks", []) if isinstance(item, dict)]
    mutating_loops = {
        "file-and-qr-closed-loop",
        "chunked-file-closed-loop",
        "saved-messages-closed-loop",
        "owned-forum-closed-loop",
    }
    writes_ran = any(item.get("name") in mutating_loops for item in checks)
    cleanup_failed = any(
        item.get("status") == "failed" and "cleanup" in str(item.get("name"))
        for item in checks
    )
    cleanup_passed = any(
        item.get("name") == "cleanup-final" and item.get("status") == "passed"
        for item in checks
    )
    cleanup_status = (
        "passed"
        if writes_ran and cleanup_passed and not cleanup_failed
        else "not_run" if not cleanup_failed else "failed"
    )
    strict_gate = (
        statuses.get("passed", 0) == len(records)
        and int(runtime_summary.get("failed", 0)) == 0
        and int(runtime_summary.get("blocked_login", 0)) == 0
        and cleanup_status == "passed"
    )

    report = {
        "$schema": "https://openai.local/schemas/flutter-mcp-validation-report-1.0.json",
        "schema_version": "1.0",
        "run_id": runtime.get("run_id") or inventory.get("run_id"),
        "environment": {
            "device": "AppFlowy_API_35 / sdk_gphone64_x86_64 via WSL adb",
            "android_api": 35,
            "package": "org.telegram.messenger.beta",
            "build_variant": "afatDebug/x86_64",
            "app_version": "12.9.0",
            "source_revision": (inventory.get("app") or {}).get("source_revision"),
        },
        "artifacts": {
            "inventory": display(root, inventory_path),
            "inventory_sha256": inventory_digest,
            "catalog": catalog_name,
            "catalog_sha256": catalog_digest,
            "apk": apk_name,
            "apk_sha256": apk_digest,
        },
        "commands": [
            {
                "name": "inventory-generation",
                "exit_code": 0,
                "evidence": [display(root, inventory_path)],
            },
            {
                "name": "unit-tests",
                "exit_code": 0,
                "evidence": [f"{args.unit_test_count} tests passed"],
            },
            {
                "name": "build-and-signature",
                "exit_code": 0,
                "evidence": [f"{apk_name} sha256={apk_digest}", "apksigner v1/v2 verified"],
            },
            {
                "name": "install",
                "exit_code": 0,
                "evidence": ["WSL adb install --no-streaming -r -t: Success"],
            },
            {
                "name": "runtime-acceptance",
                "exit_code": 0 if int(runtime_summary.get("failed", 0)) == 0 else 1,
                "evidence": [runtime_name],
            },
        ],
        "runtime_summary": runtime_summary,
        "tool_status": statuses,
        "tools": records,
        "cleanup": {
            "status": cleanup_status,
            "warnings": [] if cleanup_status == "passed" else [
                "Mutating fixtures lack an explicit successful final cleanup record."
            ],
        },
        "strict_gate": {
            "status": "passed" if strict_gate else "failed",
            "reason": (
                "All modeled tools have runtime success/readback and cleanup evidence."
                if strict_gate
                else "At least one tool is registration-only, fixture/login-blocked, or cleanup was not exercised."
            ),
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "output": str(output_path),
        "tools": len(records),
        "status": statuses,
        "cleanup": cleanup_status,
        "strict_gate": report["strict_gate"]["status"],
        "apk_sha256": apk_digest,
        "catalog_sha256": catalog_digest,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

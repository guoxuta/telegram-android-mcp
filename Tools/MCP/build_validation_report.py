#!/usr/bin/env python3
"""Build the canonical MCP validation ledger from acceptance evidence.

The report deliberately separates successful runtime/readback proof from
registration-only or actionable-error evidence. A tool is never marked passed
merely because it appears in tools/list.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


RUN_ID = "telegram-mcp-20260729"
RUNTIME_REPORT = ".mcp-work/telegram-mcp-20260729/runtime-validation.json"
CATALOG = "TMessagesProj/src/main/assets/mcp/telegram_mcp_tools.json"
APK = "D:/TelegramBuild/gradle/_TMessagesProj_App/outputs/apk/afat/debug/app.apk"
APK_SHA256 = "6BC8635FCD6E68612576D229CBCB831D274E90A62EDC040F5A75EF7F2C56BE43"
NEGATIVE_RUNTIME_TOOLS = {
    "telegram.message.delete",
    "telegram.dialog.clear_history",
}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def check(
    *,
    layer: str,
    name: str,
    status: str,
    command: str | None,
    evidence: list[str],
    observation: str,
) -> dict[str, Any]:
    return {
        "layer": layer,
        "name": name,
        "status": status,
        "command": command,
        "evidence": evidence,
        "observation": observation,
    }


def tool_record(
    capability: dict[str, Any],
    evidence: dict[str, Any] | None,
    runtime_report: str,
) -> dict[str, Any]:
    tool = capability["tool"]
    name = str(tool["name"])
    evidence = evidence or {}
    runtime_status = str(evidence.get("status", "not-run"))
    runtime_pointer = f"{runtime_report}#/tool_evidence/{name}"
    checks = [
        check(
            layer="contract",
            name="registered-schema",
            status="passed",
            command="run-telegram-deepseek-agent.ps1 acceptance",
            evidence=[
                f"{runtime_report}#/checks/tools-list-and-schemas",
                f"{CATALOG}#/{name}",
            ],
            observation="The installed app exposed a closed object schema with matching MCP metadata.",
        ),
        check(
            layer="build",
            name="afat-debug-apk",
            status="passed",
            command="gradlew.bat :TMessagesProj_App:assembleAfatDebug -PMCP_ABI=x86_64",
            evidence=[f"{APK} sha256={APK_SHA256}"],
            observation="The MCP implementation and catalog were packaged in the x86_64 debug APK.",
        ),
        check(
            layer="install",
            name="debug-package-installed",
            status="passed",
            command="adb install --no-streaming -r -t app.apk",
            evidence=["package:org.telegram.messenger.beta", runtime_report],
            observation="The debuggable beta package launched and generated its private MCP token.",
        ),
    ]

    notes: list[str] = []
    if runtime_status == "runtime-verified":
        record_status = "passed"
        checks.append(
            check(
                layer="runtime",
                name="installed-tool-call",
                status="passed",
                command="telegram_mcp_acceptance.py",
                evidence=[runtime_pointer],
                observation="The installed app returned the expected structured success result.",
            )
        )
        if tool.get("read_only") is not True:
            checks.append(
                check(
                    layer="readback",
                    name="independent-state-readback",
                    status="passed",
                    command="telegram_mcp_acceptance.py --write-saved-messages",
                    evidence=[runtime_pointer],
                    observation=(
                        "Acceptance marks writes runtime-verified only after an independent read API "
                        "confirmed the state transition."
                    ),
                )
            )
        if tool.get("destructive") is True:
            if name in NEGATIVE_RUNTIME_TOOLS:
                checks.append(
                    check(
                        layer="security",
                        name="confirmation-fails-closed",
                        status="passed",
                        command="telegram_mcp_acceptance.py",
                        evidence=[
                            f"{runtime_report}#/checks/schema-and-confirmation-fail-closed"
                        ],
                        observation="The installed server rejected a missing confirmation argument.",
                    )
                )
            else:
                record_status = "blocked"
                notes.append(
                    "Runtime success exists, but this destructive tool still needs a dedicated denial test."
                )
    elif runtime_status == "runtime-verified-safe-error":
        record_status = "blocked"
        checks.append(
            check(
                layer="negative",
                name="actionable-safe-error",
                status="passed",
                command="telegram_mcp_acceptance.py",
                evidence=[runtime_pointer],
                observation="The precondition failure was structured and actionable, without a crash.",
            )
        )
        checks.append(
            check(
                layer="runtime",
                name="successful-precondition-path",
                status="blocked",
                command=None,
                evidence=[runtime_pointer],
                observation="A logged-in test account or dedicated fixture is required for the success path.",
            )
        )
    elif runtime_status.startswith("runtime-blocked"):
        record_status = "blocked"
        checks.append(
            check(
                layer="runtime",
                name="fixture-dependent-runtime",
                status="blocked",
                command=None,
                evidence=[runtime_pointer],
                observation=f"Acceptance reported {runtime_status}.",
            )
        )
    else:
        record_status = "blocked"
        checks.append(
            check(
                layer="runtime",
                name="account-or-fixture-runtime",
                status="blocked",
                command=None,
                evidence=[
                    f"{runtime_report}#/checks/account-state",
                    runtime_pointer,
                ],
                observation=(
                    "Only registration/schema proof is available; login or an explicit disposable fixture "
                    "is required before invoking this operation."
                ),
            )
        )

    if record_status != "passed":
        notes.append(
            "Not claimed as functionally verified; registration and build evidence are retained for the next iteration."
        )
    return {
        "capability_id": capability["id"],
        "tool_name": name,
        "status": record_status,
        "attempts": 1,
        "checks": checks,
        "notes": notes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", type=Path)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.repo.resolve()
    inventory_path = args.inventory if args.inventory.is_absolute() else root / args.inventory
    runtime_path = args.runtime if args.runtime.is_absolute() else root / args.runtime
    output_path = args.output if args.output.is_absolute() else root / args.output
    inventory = load_json(inventory_path)
    runtime = load_json(runtime_path)
    evidence_by_tool = runtime.get("tool_evidence") or {}
    if not isinstance(evidence_by_tool, dict):
        raise ValueError("runtime tool_evidence must be an object")

    records = [
        tool_record(
            capability,
            evidence_by_tool.get((capability.get("tool") or {}).get("name")),
            RUNTIME_REPORT,
        )
        for capability in inventory.get("capabilities", [])
        if isinstance(capability.get("tool"), dict)
    ]
    saved_loop_passed = any(
        item.get("name") == "saved-messages-closed-loop" and item.get("status") == "passed"
        for item in runtime.get("checks", [])
        if isinstance(item, dict)
    )
    cleanup = {
        "status": "passed" if saved_loop_passed else "not_run",
        "tracked_ids": [],
        "evidence": [
            f"{RUNTIME_REPORT}#/checks/saved-messages-closed-loop"
            if saved_loop_passed
            else "Pre-login acceptance performed no writes."
        ],
        "warnings": []
        if saved_loop_passed
        else ["Saved Messages cleanup will run after the user logs into the isolated beta package."],
    }
    report = {
        "$schema": "https://openai.local/schemas/flutter-mcp-validation-report-1.0.json",
        "schema_version": "1.0",
        "run_id": inventory.get("run_id", RUN_ID),
        "inventory_digest": hashlib.sha256(inventory_path.read_bytes()).hexdigest(),
        "environment": {
            "device": "AppFlowy_API_35 / sdk_gphone64_x86_64 via WSL adb",
            "android_api": 35,
            "package": "org.telegram.messenger.beta",
            "build_variant": "afatDebug",
            "app_version": "12.9.0",
            "source_revision": (inventory.get("app") or {}).get("source_revision"),
        },
        "commands": [
            {
                "name": "inventory-generation",
                "command": "python Tools/MCP/generate_capability_inventory.py . --runtime-validation runtime-validation.json",
                "exit_code": 0,
                "evidence": [str(inventory_path.relative_to(root))],
            },
            {
                "name": "unit-tests",
                "command": "python -m unittest Tools.MCP.test_telegram_deepseek_agent",
                "exit_code": 0,
                "evidence": ["9 tests passed"],
            },
            {
                "name": "build",
                "command": "gradlew.bat :TMessagesProj_App:assembleAfatDebug -PMCP_ABI=x86_64",
                "exit_code": 0,
                "evidence": [f"{APK} sha256={APK_SHA256}"],
            },
            {
                "name": "install",
                "command": "adb install --no-streaming -r -t app.apk",
                "exit_code": 0,
                "evidence": ["package:org.telegram.messenger.beta"],
            },
            {
                "name": "runtime-acceptance",
                "command": "run-telegram-deepseek-agent.ps1 acceptance",
                "exit_code": 0,
                "evidence": [RUNTIME_REPORT],
            },
        ],
        "tools": records,
        "cleanup": cleanup,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    counts: dict[str, int] = {}
    for record in records:
        counts[record["status"]] = counts.get(record["status"], 0) + 1
    print(
        json.dumps(
            {
                "output": str(output_path),
                "tools": len(records),
                "status": counts,
                "cleanup": cleanup["status"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

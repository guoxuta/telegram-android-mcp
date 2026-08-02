import unittest
from pathlib import Path
from unittest import mock

from Tools.MCP import telegram_mcp_acceptance as acceptance_module
from Tools.MCP.telegram_mcp_acceptance import (
    Acceptance,
    redact_report_value,
    validate_tool_envelope,
)


class FakeClient:
    def __init__(self, responses):
        self.url = "http://127.0.0.1:19876/mcp"
        self.responses = list(responses)

    def call(self, name, arguments=None):
        if not self.responses:
            raise AssertionError(f"unexpected call: {name} {arguments}")
        return self.responses.pop(0)


class ReportRedactionTests(unittest.TestCase):
    def test_redacts_account_identity_and_stable_peers(self) -> None:
        report = {
            "user_id": "123456",
            "display_name": "Example User",
            "phone_number": "+10000000000",
            "peer": "user:123456",
            "nested": [{"peer": "channel:987", "message_id": 42}],
        }

        redacted = redact_report_value(report)

        self.assertEqual("<redacted>", redacted["user_id"])
        self.assertEqual("<redacted>", redacted["display_name"])
        self.assertEqual("<redacted>", redacted["phone_number"])
        self.assertEqual("<redacted>", redacted["peer"])
        self.assertEqual("<redacted>", redacted["nested"][0]["peer"])
        self.assertEqual("<redacted>", redacted["nested"][0]["message_id"])

    def test_preserves_non_sensitive_evidence(self) -> None:
        report = {
            "status": "runtime-verified",
            "counts": [1, 2, 3],
        }

        self.assertEqual(report, redact_report_value(report))

    def test_redacts_sensitive_substrings_in_diagnostics(self) -> None:
        value = (
            "failed for user:123456, +10000000000, @private_user and "
            + "a" * 64
        )

        redacted = redact_report_value(value)

        self.assertNotIn("123456", redacted)
        self.assertNotIn("+10000000000", redacted)
        self.assertNotIn("@private_user", redacted)
        self.assertNotIn("a" * 64, redacted)

    def test_preserves_named_sha256_evidence_but_not_tokens(self) -> None:
        digest = "a" * 64
        redacted = redact_report_value(
            {
                "catalog_sha256": digest,
                "sha256": digest,
                "token": digest,
                "diagnostic": f"unexpected bearer {digest}",
            }
        )

        self.assertEqual(digest, redacted["catalog_sha256"])
        self.assertEqual(digest, redacted["sha256"])
        self.assertEqual("<redacted>", redacted["token"])
        self.assertNotIn(digest, redacted["diagnostic"])


class AcceptanceStrictnessTests(unittest.TestCase):
    def tool(self):
        return {
            "name": "telegram.example.read",
            "annotations": {"readOnlyHint": True},
            "inputSchema": {"type": "object", "required": []},
        }

    def test_discriminated_tool_envelopes(self) -> None:
        validate_tool_envelope("telegram.test", {"ok": True, "data": {}}, False)
        validate_tool_envelope(
            "telegram.test",
            {
                "ok": False,
                "error": {
                    "code": "TEST_ERROR",
                    "message": "expected",
                    "retryable": False,
                },
            },
            True,
        )

    def test_tool_envelope_rejects_ambiguous_or_mismatched_results(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "invalid success envelope"):
            validate_tool_envelope(
                "telegram.test",
                {"ok": True, "data": {}, "error": {}},
                False,
            )
        with self.assertRaisesRegex(RuntimeError, "isError=false"):
            validate_tool_envelope(
                "telegram.test",
                {
                    "ok": False,
                    "error": {
                        "code": "TEST_ERROR",
                        "message": "expected",
                        "retryable": False,
                    },
                },
                False,
            )

    def test_zero_argument_read_does_not_mask_internal_error(self) -> None:
        client = FakeClient(
            [{"ok": False, "error": {"code": "TELEGRAM_ERROR", "retryable": False}}]
        )
        acceptance = Acceptance(client, Path("unused.json"))

        with self.assertRaisesRegex(RuntimeError, "TELEGRAM_ERROR"):
            acceptance.broad_zero_argument_reads([self.tool()], logged_in=True)

    def test_zero_argument_read_preserves_explicit_feature_boundary(self) -> None:
        client = FakeClient(
            [{"ok": False, "error": {"code": "PREMIUM_REQUIRED", "retryable": False}}]
        )
        acceptance = Acceptance(client, Path("unused.json"))

        acceptance.broad_zero_argument_reads([self.tool()], logged_in=True)

        self.assertEqual(
            "runtime-blocked-premium-required",
            acceptance.report["tool_evidence"]["telegram.example.read"]["status"],
        )

    def test_cleanup_keeps_tracker_and_fails_when_message_remains(self) -> None:
        client = FakeClient(
            [
                {"ok": True, "data": {}},
                {"ok": True, "data": {"messages": [{"message_id": 42}]}},
            ]
        )
        acceptance = Acceptance(client, Path("unused.json"))
        acceptance.cleanup_message_ids = [42]

        self.assertFalse(acceptance.best_effort_cleanup())
        self.assertEqual([42], acceptance.cleanup_message_ids)

    def test_cleanup_clears_tracker_only_after_absence_readback(self) -> None:
        client = FakeClient(
            [
                {"ok": True, "data": {}},
                {"ok": True, "data": {"messages": []}},
            ]
        )
        acceptance = Acceptance(client, Path("unused.json"))
        acceptance.cleanup_message_ids = [42]

        self.assertTrue(acceptance.best_effort_cleanup())
        self.assertEqual([], acceptance.cleanup_message_ids)

    def test_chat_absence_does_not_mask_internal_error(self) -> None:
        client = FakeClient(
            [{"ok": False, "error": {"code": "INTERNAL_ERROR", "retryable": True}}]
        )
        acceptance = Acceptance(client, Path("unused.json"))

        with self.assertRaisesRegex(RuntimeError, "INTERNAL_ERROR"):
            acceptance.assert_chat_absent("channel:1")

    def test_destructive_guard_requires_confirm_specific_failure(self) -> None:
        client = FakeClient(
            [
                {
                    "ok": False,
                    "error": {
                        "code": "INVALID_ARGUMENT",
                        "message": "arguments is missing required property _confirm",
                    },
                }
            ]
        )
        acceptance = Acceptance(client, Path("unused.json"))
        tool = {
            "name": "telegram.example.delete",
            "annotations": {"destructiveHint": True},
            "inputSchema": {
                "type": "object",
                "properties": {
                    "peer": {"type": "string", "minLength": 1},
                    "_confirm": {"type": "boolean", "const": True},
                },
                "required": ["peer", "_confirm"],
            },
        }

        acceptance.destructive_confirmation_guards([tool])

        self.assertTrue(
            acceptance.report["tool_evidence"]["telegram.example.delete"][
                "confirmation_guard"
            ]
        )

    def test_logged_out_write_run_still_exercises_local_file_loops(self) -> None:
        calls = []

        class FakeAcceptance:
            def __init__(self, client, report_path):
                self.client = client
                self.cleanup_file_refs = []
                self.cleanup_upload_refs = []
                self.cleanup_message_ids = []
                self.cleanup_folder_ids = []
                self.cleanup_chat_peer = ""

            def protocol(self, token):
                calls.append("protocol")
                return [], None

            def required_argument_guards(self, tools):
                calls.append("required-guards")

            def destructive_confirmation_guards(self, tools):
                calls.append("destructive-guards")

            def safe_account_reads(self):
                calls.append("account")
                return False

            def broad_zero_argument_reads(self, tools, *, logged_in):
                calls.append("zero-argument")

            def file_and_qr_loop(self):
                calls.append("file-and-qr")

            def chunked_file_loop(self):
                calls.append("chunked-file")

            def check(self, name, status, detail=None, error=None):
                calls.append((name, status))

            def best_effort_cleanup(self):
                calls.append("cleanup")
                return True

            def finish(self):
                return {"summary": {}}

        class FakeRpcClient:
            def __init__(self, url, token):
                pass

            def wait_until_ready(self):
                calls.append("ready")

        with mock.patch.object(acceptance_module, "Acceptance", FakeAcceptance), mock.patch.object(
            acceptance_module, "RpcClient", FakeRpcClient
        ):
            exit_code, _ = acceptance_module.run_acceptance(
                url="http://127.0.0.1:19876/mcp",
                token="test-token",
                report_path=Path("unused.json"),
                write_saved_messages=True,
            )

        self.assertEqual(1, exit_code)
        self.assertLess(calls.index("file-and-qr"), calls.index(("runtime", "failed")))
        self.assertLess(calls.index("chunked-file"), calls.index(("runtime", "failed")))
        self.assertIn("cleanup", calls)
        self.assertIn(("cleanup-final", "passed"), calls)


if __name__ == "__main__":
    unittest.main()

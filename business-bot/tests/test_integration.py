#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""End-to-end: настоящий bot.py + mock Bot API по HTTP.

Проверяем весь путь CLI: getMe → getWebhookInfo → deleteWebhook → setMyCommands →
getUpdates → deleteMessage → sendMessage, без реального Telegram.
"""

from __future__ import annotations

import json
import os
import signal
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import bot as topbot  # noqa: E402
from test_bot import MockTelegramServer  # noqa: E402


def update_with_top(update_id: int = 1, message_id: int = 10) -> dict:
    return {
        "ok": True,
        "result": [
            {
                "update_id": update_id,
                "message": {
                    "message_id": message_id,
                    "date": int(time.time()),
                    "chat": {"id": 100, "type": "private"},
                    "from": {"id": 100, "is_bot": False, "first_name": "Tester"},
                    "text": "/top",
                },
            }
        ],
    }


class IntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        (self.dir / "top.json").write_text(
            json.dumps({"title": "ТОП", "entries": ["Misha", "Misha", "misha"]}, ensure_ascii=False),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def env(self, base: str) -> dict:
        return {
            "BOT_TOKEN": "123456:INTEGRATION",
            "API_BASE": base,
            "TOP_FILE": str(self.dir / "top.json"),
            "STATE_FILE": str(self.dir / "state.json"),
            "LOG_FILE": str(self.dir / "bot.log"),
            "COOLDOWN_SECONDS": "0",
            "POLL_TIMEOUT": "0",
            "MODE": "private",
            "DELETE_COMMAND": "true",
            "SET_COMMANDS_MENU": "true",
            "BOT_ENV_FILE": "",
        }

    def run_main(self, argv: list) -> int:
        old_handlers = {}
        for sig in (signal.SIGINT, signal.SIGTERM):
            old_handlers[sig] = signal.getsignal(sig)
        try:
            return topbot.main(argv)
        finally:
            for sig, handler in old_handlers.items():
                try:
                    signal.signal(sig, handler)
                except (ValueError, OSError):
                    pass

    def test_once_processes_top_end_to_end(self) -> None:
        responses = [
            {"ok": True, "result": {"id": 1, "is_bot": True, "first_name": "T", "username": "t_bot"}},
            {"ok": True, "result": {"url": ""}},
            {"ok": True, "result": True},
            {"ok": True, "result": True},
            update_with_top(),
            {"ok": True, "result": True},
            {"ok": True, "result": {"message_id": 500, "date": int(time.time())}},
        ]
        with MockTelegramServer(responses) as server:
            with mock.patch.dict(os.environ, self.env(f"http://127.0.0.1:{server.port}"), clear=False):
                code = self.run_main(["--once"])
            self.assertEqual(code, 0)

            methods = [r["path"].rsplit("/", 1)[-1] for r in server.requests]
            self.assertEqual(
                methods,
                [
                    "getMe",
                    "getWebhookInfo",
                    "deleteWebhook",
                    "setMyCommands",
                    "getUpdates",
                    "deleteMessage",
                    "sendMessage",
                ],
            )
            sent = server.requests[-1]["body"]
            self.assertEqual(sent["chat_id"], 100)
            self.assertIn("1. Misha", sent["text"])
            self.assertIn("2. Misha", sent["text"])
            self.assertIn("3. misha", sent["text"])
            self.assertEqual(server.requests[-2]["body"]["message_id"], 10)

        state = json.loads((self.dir / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(state["offset"], 2)
        self.assertEqual(state["stats"]["tops_sent"], 1)
        self.assertEqual(state["stats"]["messages_deleted"], 1)

    def test_check_reports_ready(self) -> None:
        responses = [
            {"ok": True, "result": {"id": 1, "is_bot": True, "first_name": "T", "username": "t_bot"}},
            {"ok": True, "result": {"url": ""}},
            {"ok": True, "result": True},
        ]
        with MockTelegramServer(responses) as server:
            with mock.patch.dict(os.environ, self.env(f"http://127.0.0.1:{server.port}"), clear=False):
                code = self.run_main(["--check"])
            self.assertEqual(code, 0)
            methods = [r["path"].rsplit("/", 1)[-1] for r in server.requests]
            self.assertEqual(methods, ["getMe", "getWebhookInfo", "deleteWebhook"])

    def test_check_fails_with_bad_token(self) -> None:
        responses = [
            {
                "_status": 401,
                "ok": False,
                "error_code": 401,
                "description": "Unauthorized",
            }
        ]
        with MockTelegramServer(responses) as server:
            with mock.patch.dict(os.environ, self.env(f"http://127.0.0.1:{server.port}"), clear=False):
                code = self.run_main(["--check"])
            self.assertEqual(code, 1)

    def test_conflict_stops_with_code_2(self) -> None:
        responses = [
            {"ok": True, "result": {"id": 1, "is_bot": True, "first_name": "T", "username": "t_bot"}},
            {"ok": True, "result": {"url": ""}},
            {"ok": True, "result": True},
            {"ok": True, "result": True},
            {
                "_status": 409,
                "ok": False,
                "error_code": 409,
                "description": "Conflict: terminated by other getUpdates request",
            },
        ]
        with MockTelegramServer(responses) as server:
            with mock.patch.dict(os.environ, self.env(f"http://127.0.0.1:{server.port}"), clear=False):
                code = self.run_main(["--once"])
            self.assertEqual(code, 2)

    def test_print_top_does_not_touch_network(self) -> None:
        with MockTelegramServer([]) as server:
            with mock.patch.dict(os.environ, self.env(f"http://127.0.0.1:{server.port}"), clear=False):
                code = self.run_main(["--print-top"])
            self.assertEqual(code, 0)
            self.assertEqual(server.requests, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)

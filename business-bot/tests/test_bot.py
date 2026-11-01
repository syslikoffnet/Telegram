#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Офлайн-тесты TOP-бота: логика + транспорт Bot API на локальном mock-сервере.

Запуск:  python3 -m unittest discover -s tests -v
Нужен только Python 3.9+ (никаких сторонних пакетов).
"""

from __future__ import annotations

import json
import logging
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import bot as topbot  # noqa: E402


# ---------------------------------------------------------------------------
# Общие помощники
# ---------------------------------------------------------------------------


def null_logger() -> logging.Logger:
    """Тихий логгер: тесты не должны писать в bot.log."""
    logger = logging.getLogger("topbot-tests")
    logger.handlers = [logging.NullHandler()]
    logger.propagate = False
    logger.setLevel(logging.CRITICAL)
    return logger


def make_config(**env: Any) -> topbot.Config:
    """Конфиг без чтения реального .env."""
    overrides = {k.upper(): str(v) for k, v in env.items()}
    return topbot.load_config(env_path="/nonexistent/.env", overrides=overrides)


def private_message(
    chat_id: int = 100,
    message_id: int = 10,
    text: str = "/top",
    date: Optional[int] = None,
    user_id: Optional[int] = None,
    chat_type: str = "private",
    business_connection_id: Optional[str] = None,
) -> Dict[str, Any]:
    message: Dict[str, Any] = {
        "message_id": message_id,
        "date": int(time.time()) if date is None else date,
        "chat": {"id": chat_id, "type": chat_type},
        "from": {"id": user_id if user_id is not None else chat_id, "is_bot": False},
        "text": text,
    }
    if business_connection_id:
        message["business_connection_id"] = business_connection_id
    return message


class BotFixture(unittest.TestCase):
    """Каждый тест получает свой временный каталог с top.json/state."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.write_top(["Misha", "Misha", "misha"])
        self.logger = null_logger()

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write_top(self, entries: Any) -> None:
        (self.dir / "top.json").write_text(
            json.dumps({"title": "ТОП", "entries": entries}, ensure_ascii=False), encoding="utf-8"
        )

    def make_bot(self, **env: Any):
        base = {
            "BOT_TOKEN": "123456:SELFTEST",
            "TOP_FILE": str(self.dir / "top.json"),
            "STATE_FILE": str(self.dir / "state.json"),
            "LOG_FILE": str(self.dir / "bot.log"),
            "COOLDOWN_SECONDS": "0",
            "MAX_TOPS_PER_MINUTE": "100",
        }
        base.update(env)
        cfg = make_config(**base)
        cfg.base_dir = self.dir
        cfg.state_file = Path(base["STATE_FILE"])
        cfg.top_file = Path(base["TOP_FILE"])
        cfg.log_file = Path(base["LOG_FILE"])
        logger = null_logger()
        api = topbot.FakeTelegramAPI()
        state = topbot.State(cfg.state_file, logger)
        clock = [1000.0]
        bot = topbot.Bot(cfg, api, state=state, logger=logger, clock=lambda: clock[0])
        return bot, api, clock


# ---------------------------------------------------------------------------
# Конфиг
# ---------------------------------------------------------------------------


class ConfigTests(unittest.TestCase):
    def test_env_file_parsing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / ".env"
            env.write_text(
                "# comment\n"
                "BOT_TOKEN=123456789:AAHtest\n"
                "export COMMAND=/top, /toplist\n"
                "TOP_HEADER='🏆 <b>{title}</b>'\n"
                'ITEM_SEPARATOR="\\n\\n"\n'
                "ALLOWED_USER_IDS=1, 2,3\n"
                "SHOW_MEDALS=yes\n",
                encoding="utf-8",
            )
            cfg = topbot.load_config(str(env))
            self.assertEqual(cfg.token, "123456789:AAHtest")
            self.assertEqual(cfg.commands, ["/top", "/toplist"])
            self.assertEqual(cfg.header, "🏆 <b>{title}</b>")
            self.assertEqual(cfg.separator, "\n\n")
            self.assertEqual(cfg.allowed_users, {1, 2, 3})
            self.assertTrue(cfg.show_medals)

    def test_env_overrides_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / ".env"
            env.write_text("BOT_TOKEN=from-file\nCOOLDOWN_SECONDS=5\n", encoding="utf-8")
            cfg = topbot.load_config(str(env), overrides={"BOT_TOKEN": "from-cli"})
            self.assertEqual(cfg.token, "from-cli")
            self.assertEqual(cfg.cooldown_seconds, 5.0)

    def test_invalid_mode_falls_back_to_auto(self) -> None:
        cfg = make_config(mode="weird")
        self.assertEqual(cfg.mode, "auto")

    def test_mask_token_never_leaks_full_token(self) -> None:
        token = "123456789:AAHsecretsecretsecret"
        masked = topbot.mask_token(token)
        self.assertNotIn("secretsecret", masked)


# ---------------------------------------------------------------------------
# Данные топа
# ---------------------------------------------------------------------------


class TopDataTests(BotFixture):
    def test_json_list_renders_numbers(self) -> None:
        self.write_top(["Misha", "Misha", "misha"])
        data = topbot.TopData(make_config(top_file=str(self.dir / "top.json")), self.logger)
        text = data.render()
        self.assertIn("1. Misha", text)
        self.assertIn("2. Misha", text)
        self.assertIn("3. misha", text)

    def test_json_objects_with_scores_and_sort(self) -> None:
        self.write_top([{"name": "Ann", "score": 10}, {"name": "Bob", "score": 50}])
        cfg = make_config(top_file=str(self.dir / "top.json"), sort_by_score="true")
        data = topbot.TopData(cfg, self.logger)
        text = data.render()
        self.assertLess(text.index("Bob"), text.index("Ann"))
        self.assertIn("50", text)

    def test_plain_text_file_with_scores(self) -> None:
        path = self.dir / "top.txt"
        path.write_text("# комментарий\nMisha | 100\nOleg\n", encoding="utf-8")
        data = topbot.TopData(make_config(top_file=str(path)), self.logger)
        text = data.render()
        self.assertIn("1. Misha", text)
        self.assertIn("2. Oleg", text)
        self.assertIn("100", text)

    def test_title_from_json(self) -> None:
        (self.dir / "top.json").write_text(
            json.dumps({"title": "Мой топ", "entries": ["A"]}, ensure_ascii=False), encoding="utf-8"
        )
        data = topbot.TopData(make_config(top_file=str(self.dir / "top.json")), self.logger)
        self.assertIn("Мой топ", data.render())

    def test_html_is_escaped(self) -> None:
        self.write_top(["<script>x</script> & co"])
        data = topbot.TopData(make_config(top_file=str(self.dir / "top.json")), self.logger)
        text = data.render()
        self.assertIn("&lt;script&gt;x&lt;/script&gt; &amp; co", text)

    def test_parse_mode_none_keeps_raw(self) -> None:
        self.write_top(["<b>x</b>"])
        cfg = make_config(top_file=str(self.dir / "top.json"), parse_mode="none")
        data = topbot.TopData(cfg, self.logger)
        self.assertIn("<b>x</b>", data.render())

    def test_limit_and_medals(self) -> None:
        self.write_top(["A", "B", "C", "D"])
        cfg = make_config(top_file=str(self.dir / "top.json"), top_limit="2", show_medals="true")
        text = topbot.TopData(cfg, self.logger).render()
        self.assertIn("🥇 1. A", text)
        self.assertIn("🥈 2. B", text)
        self.assertNotIn("D", text)

    def test_separator_blank_lines(self) -> None:
        self.write_top(["A", "B"])
        cfg = make_config(top_file=str(self.dir / "top.json"), item_separator="\\n\\n")
        text = topbot.TopData(cfg, self.logger).render()
        self.assertIn("1. A\n\n2. B", text)

    def test_missing_file_falls_back(self) -> None:
        data = topbot.TopData(make_config(top_file=str(self.dir / "nope.json")), self.logger)
        self.assertEqual([e.name for e in data.entries], topbot.DEFAULT_TOP_FALLBACK)

    def test_broken_json_keeps_previous_list(self) -> None:
        cfg = make_config(top_file=str(self.dir / "top.json"))
        data = topbot.TopData(cfg, self.logger)
        self.write_top(["X", "Y"])
        data.render()  # подхватили новый файл
        (self.dir / "top.json").write_text("{ broken json", encoding="utf-8")
        text = data.render()
        self.assertIn("1. X", text, "при битом JSON должен остаться предыдущий список")

    def test_hot_reload_by_mtime(self) -> None:
        cfg = make_config(top_file=str(self.dir / "top.json"))
        data = topbot.TopData(cfg, self.logger)
        self.assertIn("Misha", data.render())
        time.sleep(0.01)
        self.write_top(["Newcomer"])
        import os

        os.utime(self.dir / "top.json", (time.time() + 5, time.time() + 5))
        self.assertIn("Newcomer", data.render())


class SplitTextTests(unittest.TestCase):
    def test_short_text_single_chunk(self) -> None:
        self.assertEqual(topbot.split_text("hello"), ["hello"])

    def test_splits_by_lines_under_limit(self) -> None:
        text = "\n".join(f"{i}. " + "x" * 100 for i in range(100))
        chunks = topbot.split_text(text, limit=500)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(topbot.utf16_len(c) <= 500 for c in chunks))
        self.assertEqual("".join(chunks).replace("\n", ""), text.replace("\n", ""))

    def test_hard_split_long_line(self) -> None:
        chunks = topbot.split_text("y" * 1000, limit=100)
        self.assertTrue(all(topbot.utf16_len(c) <= 100 for c in chunks))
        self.assertEqual("".join(chunks), "y" * 1000)

    def test_emoji_counts_as_two(self) -> None:
        self.assertEqual(topbot.utf16_len("🏆"), 2)
        chunks = topbot.split_text("🏆" * 100, limit=50)
        self.assertTrue(all(topbot.utf16_len(c) <= 50 for c in chunks))


already_deleted = object()


# ---------------------------------------------------------------------------
# Поведение бота
# ---------------------------------------------------------------------------


class BotBehaviourTests(BotFixture):
    def test_top_deletes_command_then_sends_new_message(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message()})

        self.assertEqual(api.methods(), ["deleteMessage", "sendMessage"])
        self.assertEqual(api.only("deleteMessage")[0], {"chat_id": 100, "message_id": 10})
        sent = api.only("sendMessage")[0]
        self.assertEqual(sent["chat_id"], 100)
        self.assertIn("1. Misha\n2. Misha\n3. misha", sent["text"])
        self.assertEqual(sent["parse_mode"], "HTML")
        self.assertNotIn("editMessageText", api.methods())

    def test_each_top_is_a_new_message(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message(message_id=10)})
        bot.handle_update({"update_id": 2, "message": private_message(message_id=11)})
        self.assertEqual(len(api.only("sendMessage")), 2)
        self.assertEqual(len(api.only("deleteMessage")), 2)
        self.assertEqual(api.only("deleteMessage")[1]["message_id"], 11)
        self.assertNotIn("editMessageText", api.methods())

    def test_command_with_bot_username(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message(text="/top@my_top_bot")})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_command_case_insensitive(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message(text="/TOP")})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_unknown_command_and_plain_text_ignored(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message(text="/start")})
        api.clear()
        bot.handle_update({"update_id": 2, "message": private_message(text="топ?")})
        bot.handle_update({"update_id": 3, "message": private_message(text="/toplist")})
        self.assertEqual(api.calls, [])

    def test_cooldown_blocks_repeat(self) -> None:
        bot, api, clock = self.make_bot(COOLDOWN_SECONDS="5")
        bot.handle_update({"update_id": 1, "message": private_message(message_id=10)})
        bot.handle_update({"update_id": 2, "message": private_message(message_id=11)})
        self.assertEqual(len(api.only("sendMessage")), 1)
        clock[0] += 6
        bot.handle_update({"update_id": 3, "message": private_message(message_id=12)})
        self.assertEqual(len(api.only("sendMessage")), 2)

    def test_per_minute_limit(self) -> None:
        bot, api, clock = self.make_bot(COOLDOWN_SECONDS="0", MAX_TOPS_PER_MINUTE="2")
        for i in range(5):
            bot.handle_update({"update_id": i, "message": private_message(message_id=10 + i)})
            clock[0] += 0.1
        self.assertEqual(len(api.only("sendMessage")), 2)
        clock[0] += 61
        bot.handle_update({"update_id": 99, "message": private_message(message_id=99)})
        self.assertEqual(len(api.only("sendMessage")), 3)

    def test_stale_message_skipped(self) -> None:
        bot, api, _ = self.make_bot(MAX_MESSAGE_AGE_SECONDS="60")
        old = private_message(date=int(time.time()) - 600)
        bot.handle_update({"update_id": 1, "message": old})
        self.assertEqual(api.calls, [])

    def test_stale_check_can_be_disabled(self) -> None:
        bot, api, _ = self.make_bot(MAX_MESSAGE_AGE_SECONDS="0")
        old = private_message(date=int(time.time()) - 600)
        bot.handle_update({"update_id": 1, "message": old})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_whitelist_users(self) -> None:
        bot, api, _ = self.make_bot(ALLOWED_USER_IDS="777")
        bot.handle_update({"update_id": 1, "message": private_message(user_id=100)})
        self.assertEqual(api.calls, [])
        bot.handle_update({"update_id": 2, "message": private_message(user_id=777)})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_groups_disabled_by_default(self) -> None:
        bot, api, _ = self.make_bot()
        group = private_message(chat_id=-100123, chat_type="supergroup")
        bot.handle_update({"update_id": 1, "message": group})
        self.assertEqual(api.calls, [])

    def test_groups_enabled(self) -> None:
        bot, api, _ = self.make_bot(ALLOW_GROUPS="true")
        group = private_message(chat_id=-100123, chat_type="supergroup")
        bot.handle_update({"update_id": 1, "message": group})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_bots_are_ignored(self) -> None:
        bot, api, _ = self.make_bot()
        msg = private_message()
        msg["from"] = {"id": 999, "is_bot": True}
        bot.handle_update({"update_id": 1, "message": msg})
        self.assertEqual(api.calls, [])

    def test_delete_disabled_still_sends(self) -> None:
        bot, api, _ = self.make_bot(DELETE_COMMAND="false")
        bot.handle_update({"update_id": 1, "message": private_message()})
        self.assertEqual(api.methods(), ["sendMessage"])

    def test_delete_failure_does_not_block_send(self) -> None:
        bot, api, _ = self.make_bot()
        api.fail_on["deleteMessage"] = topbot.TelegramError("Bad Request: message can't be deleted", 400)
        bot.handle_update({"update_id": 1, "message": private_message()})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_already_deleted_is_not_an_error(self) -> None:
        bot, api, _ = self.make_bot()
        api.fail_on["deleteMessage"] = topbot.TelegramError(
            "Bad Request: message to delete not found", 400
        )
        bot.handle_update({"update_id": 1, "message": private_message()})
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_send_failure_does_not_crash_bot(self) -> None:
        bot, api, _ = self.make_bot()
        api.fail_on["sendMessage"] = topbot.TelegramError("Forbidden: bot was blocked by the user", 403)
        bot.handle_update({"update_id": 1, "message": private_message()})
        bot.handle_update({"update_id": 2, "message": private_message(message_id=11)})
        self.assertEqual(len(api.only("deleteMessage")), 2)

    def test_business_deletes_via_delete_business_messages(self) -> None:
        bot, api, _ = self.make_bot()
        bot.state.connections["bc-1"] = {
            "rights": {"can_delete_all_messages": True, "can_reply": True}
        }
        msg = private_message(business_connection_id="bc-1")
        bot.handle_update({"update_id": 1, "business_message": msg})

        self.assertIn("deleteBusinessMessages", api.methods())
        delete = api.only("deleteBusinessMessages")[0]
        self.assertEqual(delete["business_connection_id"], "bc-1")
        self.assertEqual(delete["message_ids"], [10])
        send = api.only("sendMessage")[0]
        self.assertEqual(send["business_connection_id"], "bc-1")

    def test_business_without_delete_right_skips_delete_but_sends(self) -> None:
        bot, api, _ = self.make_bot()
        bot.state.connections["bc-2"] = {
            "rights": {"can_delete_all_messages": False, "can_reply": True}
        }
        bot.handle_update(
            {"update_id": 1, "business_message": private_message(business_connection_id="bc-2")}
        )
        self.assertNotIn("deleteBusinessMessages", api.methods())
        self.assertEqual(len(api.only("sendMessage")), 1)

    def test_disabled_business_connection_is_ignored(self) -> None:
        bot, api, _ = self.make_bot()
        bot.state.connections["bc-3"] = {
            "is_enabled": False,
            "rights": {"can_delete_all_messages": True, "can_reply": True},
        }
        bot.handle_update(
            {"update_id": 1, "business_message": private_message(business_connection_id="bc-3")}
        )
        self.assertEqual(api.calls, [])

    def test_business_connection_update_is_stored(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update(
            {
                "update_id": 1,
                "business_connection": {
                    "id": "bc-9",
                    "user_chat_id": 555,
                    "is_enabled": True,
                    "rights": {"can_reply": True, "can_delete_all_messages": True},
                },
            }
        )
        self.assertIn("bc-9", bot.state.connections)
        self.assertTrue(bot.state.connections["bc-9"]["rights"]["can_delete_all_messages"])
        self.assertEqual(api.calls, [])

    def test_private_mode_ignores_business_messages(self) -> None:
        bot, api, _ = self.make_bot(MODE="private")
        bot.handle_update(
            {"update_id": 1, "business_message": private_message(business_connection_id="bc-1")}
        )
        self.assertEqual(api.calls, [])

    def test_business_mode_ignores_plain_messages(self) -> None:
        bot, api, _ = self.make_bot(MODE="business")
        bot.handle_update({"update_id": 1, "message": private_message()})
        self.assertEqual(api.calls, [])

    def test_reply_to_command_when_enabled(self) -> None:
        bot, api, _ = self.make_bot(REPLY_TO_COMMAND="true")
        bot.handle_update({"update_id": 1, "message": private_message(message_id=77)})
        send = api.only("sendMessage")[0]
        self.assertEqual(send["reply_parameters"]["message_id"], 77)

    def test_delete_previous_tops(self) -> None:
        bot, api, clock = self.make_bot(DELETE_PREVIOUS_TOPS="true", COOLDOWN_SECONDS="0")
        bot.handle_update({"update_id": 1, "message": private_message(message_id=10)})
        first_id = api.only("sendMessage")[0] and bot.state.last_top["dm:100"][0]
        clock[0] += 10
        bot.handle_update({"update_id": 2, "message": private_message(message_id=11)})
        self.assertIn(first_id, [p["message_id"] for p in api.only("deleteMessage")])

    def test_split_long_top(self) -> None:
        long_entries = [f"User{i} " + "z" * 200 for i in range(30)]
        self.write_top(long_entries)
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message()})
        sends = api.only("sendMessage")
        self.assertGreater(len(sends), 1)
        self.assertTrue(all(topbot.utf16_len(s["text"]) <= topbot.TELEGRAM_TEXT_LIMIT for s in sends))
        self.assertTrue(all(s["text"] for s in sends))
        self.assertEqual(len(bot.state.last_top["dm:100"]), len(sends))

    def test_id_command(self) -> None:
        bot, api, _ = self.make_bot()
        bot.handle_update({"update_id": 1, "message": private_message(text="/id")})
        text = api.only("sendMessage")[0]["text"]
        self.assertIn("chat_id: 100", text)

    def test_reload_requires_admin(self) -> None:
        bot, api, _ = self.make_bot(ADMIN_IDS="")
        bot.handle_update({"update_id": 1, "message": private_message(text="/reload")})
        self.assertEqual(api.calls, [])
        bot2, api2, _ = self.make_bot(ADMIN_IDS="100")
        bot2.handle_update({"update_id": 1, "message": private_message(text="/reload")})
        self.assertIn("перечитан", api2.only("sendMessage")[0]["text"].lower())

    def test_offset_advances_and_persists(self) -> None:
        bot, api, _ = self.make_bot()
        api.call = lambda method, params=None: (  # type: ignore[method-assign]
            [
                {"update_id": 10, "message": private_message(message_id=10)},
                {"update_id": 11, "message": private_message(message_id=11)},
            ]
            if method == "getUpdates"
            else topbot.FakeTelegramAPI.call(api, method, params)
        )
        bot.poll_once()
        self.assertEqual(bot.offset, 12)
        saved = json.loads(Path(bot.cfg.state_file).read_text(encoding="utf-8"))
        self.assertEqual(saved["offset"], 12)
        self.assertEqual(saved["stats"]["tops_sent"], 2)

    def test_dry_run_does_not_touch_network(self) -> None:
        with MockTelegramServer([]) as server:
            api = topbot.TelegramAPI(
                token="123456:TESTTOKEN",
                api_base=f"http://127.0.0.1:{server.port}",
                poll_timeout=1,
                dry_run=True,
                logger=null_logger(),
            )
            api.dry_run = True
            bot, _, _ = self.make_bot()
            bot.api = api
            bot.handle_update({"update_id": 1, "message": private_message()})
            self.assertEqual(server.requests, [])


# ---------------------------------------------------------------------------
# Транспорт Bot API против локального mock-сервера
# ---------------------------------------------------------------------------


class MockTelegramServer:
    def __init__(self, responses: List[Dict[str, Any]]) -> None:
        self.responses = list(responses)
        self.requests: List[Dict[str, Any]] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length).decode("utf-8") or "{}"
                try:
                    body = json.loads(raw)
                except json.JSONDecodeError:
                    body = {}
                outer.requests.append({"path": self.path, "body": body})
                item = outer.responses.pop(0) if outer.responses else {"ok": True, "result": True}
                status = item.get("_status", 200)
                payload = {k: v for k, v in item.items() if k != "_status"}
                data = item.get("_raw")
                if data is None:
                    data = json.dumps(payload, ensure_ascii=False)
                data_bytes = data.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data_bytes)))
                self.end_headers()
                self.wfile.write(data_bytes)

            def log_message(self, *args: Any) -> None:  # тишина в тестах
                return

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self) -> "MockTelegramServer":
        self.thread.start()
        return self

    def __exit__(self, *exc: Any) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


class TransportTests(unittest.TestCase):
    def make_api(self, server: MockTelegramServer, sleeps: Optional[List[float]] = None) -> topbot.TelegramAPI:
        recorded = sleeps if sleeps is not None else []

        def fake_sleep(seconds: float) -> None:
            recorded.append(seconds)

        return topbot.TelegramAPI(
            token="123456:TESTTOKEN",
            api_base=f"http://127.0.0.1:{server.port}",
            poll_timeout=1,
            logger=null_logger(),
            sleep=fake_sleep,
        )

    def test_call_builds_correct_url_and_returns_result(self) -> None:
        with MockTelegramServer([{"ok": True, "result": {"id": 5, "username": "me_bot"}}]) as server:
            api = self.make_api(server)
            result = api.get_me()
            self.assertEqual(result["username"], "me_bot")
            self.assertEqual(server.requests[0]["path"], "/bot123456:TESTTOKEN/getMe")

    def test_send_message_disables_preview_and_sets_mode(self) -> None:
        with MockTelegramServer([{"ok": True, "result": {"message_id": 5}}]) as server:
            api = self.make_api(server)
            api.send_message(100, "hi", parse_mode="HTML")
            body = server.requests[0]["body"]
            self.assertEqual(body["chat_id"], 100)
            self.assertEqual(body["parse_mode"], "HTML")
            self.assertEqual(body["link_preview_options"], {"is_disabled": True})

    def test_delete_message_uses_business_method(self) -> None:
        with MockTelegramServer(
            [{"ok": True, "result": True}, {"ok": True, "result": True}]
        ) as server:
            api = self.make_api(server)
            api.delete_message(1, 2, None)
            api.delete_message(1, 2, "bc-1")
            self.assertEqual(server.requests[0]["path"], "/bot123456:TESTTOKEN/deleteMessage")
            self.assertEqual(server.requests[0]["body"], {"chat_id": 1, "message_id": 2})
            self.assertEqual(server.requests[1]["path"], "/bot123456:TESTTOKEN/deleteBusinessMessages")
            self.assertEqual(
                server.requests[1]["body"],
                {"business_connection_id": "bc-1", "message_ids": [2]},
            )

    def test_400_raises_telegram_error_with_description(self) -> None:
        with MockTelegramServer(
            [
                {
                    "_status": 400,
                    "ok": False,
                    "error_code": 400,
                    "description": "Bad Request: message can't be deleted",
                }
            ]
        ) as server:
            api = self.make_api(server)
            with self.assertRaises(topbot.TelegramError) as ctx:
                api.delete_message(1, 2)
            self.assertEqual(ctx.exception.code, 400)
            self.assertIn("message can't be deleted", ctx.exception.description)

    def test_429_waits_and_retries(self) -> None:
        sleeps: List[float] = []
        with MockTelegramServer(
            [
                {
                    "_status": 429,
                    "ok": False,
                    "error_code": 429,
                    "description": "Too Many Requests: retry after 7",
                    "parameters": {"retry_after": 7},
                },
                {"ok": True, "result": {"message_id": 9}},
            ]
        ) as server:
            api = self.make_api(server, sleeps)
            result = api.send_message(1, "x")
            self.assertEqual(result["message_id"], 9)
            self.assertEqual(len(server.requests), 2)
            self.assertTrue(sleeps and sleeps[0] >= 7.0)
            self.assertLess(sleeps[0], 8.0)

    def test_non_json_response_gives_clear_error(self) -> None:
        with MockTelegramServer([{"_raw": "<html>blocked by proxy</html>"}]) as server:
            api = self.make_api(server)
            with self.assertRaises(topbot.TelegramError) as ctx:
                api.get_me()
            self.assertIn("не JSON", str(ctx.exception))

    def test_network_error_retries_then_raises(self) -> None:
        api = topbot.TelegramAPI(
            token="123456:TESTTOKEN",
            api_base="http://127.0.0.1:1",
            poll_timeout=1,
            logger=null_logger(),
            sleep=lambda _s: None,
            max_network_retries=1,
        )
        with self.assertRaises(topbot.TelegramError) as ctx:
            api.get_me()
        self.assertEqual(ctx.exception.code, -1)

    def test_get_updates_params(self) -> None:
        with MockTelegramServer([{"ok": True, "result": []}]) as server:
            api = self.make_api(server)
            api.get_updates(offset=42, timeout=5)
            body = server.requests[0]["body"]
            self.assertEqual(body["offset"], 42)
            self.assertEqual(body["timeout"], 5)
            self.assertIn("business_message", body["allowed_updates"])
            self.assertIn("business_connection", body["allowed_updates"])

    def test_token_is_not_sent_in_body(self) -> None:
        with MockTelegramServer([{"ok": True, "result": True}]) as server:
            api = self.make_api(server)
            api.delete_webhook()
            self.assertNotIn("TESTTOKEN", json.dumps(server.requests[0]["body"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)

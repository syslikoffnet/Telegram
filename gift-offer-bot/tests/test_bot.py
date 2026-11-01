#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Юнит-тесты Gift Offer Bot. Только стандартная библиотека.

Запуск:
    python3 -m unittest discover -s tests -v
    ./run-tests.sh
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import bot as ob  # noqa: E402


# ---------------------------------------------------------------------------
# Хелперы
# ---------------------------------------------------------------------------


def make_cfg(**kwargs):
    cfg = ob.Config()
    cfg.token = "123456:TEST"
    cfg.create_cooldown = 0
    cfg.db_path = Path(":memory:")
    cfg.log_file = Path("test.log")
    cfg.board_chat_id = None
    for key, value in kwargs.items():
        setattr(cfg, key, value)
    return cfg


TEST_LOG = ob.logging.getLogger("test")
TEST_LOG.propagate = False
if not TEST_LOG.handlers:
    TEST_LOG.addHandler(ob.logging.NullHandler())


def make_bot(**cfg_kwargs):
    cfg = make_cfg(**cfg_kwargs)
    api = ob.FakeTelegramAPI()
    db = ob.DB(":memory:")
    bot = ob.OfferBot(cfg, api, db, logger=ob.logging.getLogger("test"))
    bot.username = "gift_offer_bot"
    return bot, api, db, cfg


def dm(text, user_id=100, message_id=10, first_name="Tester"):
    return {
        "message_id": message_id,
        "date": int(time.time()),
        "chat": {"id": user_id, "type": "private"},
        "from": {"id": user_id, "is_bot": False, "first_name": first_name},
        "text": text,
    }


def upd(update_id, message=None, callback=None):
    data = {"update_id": update_id}
    if message is not None:
        data["message"] = message
    if callback is not None:
        data["callback_query"] = callback
    return data


def cb(data, user_id, chat_id=None, message_id=501, cb_id="cb"):
    return {
        "id": cb_id,
        "from": {"id": user_id, "is_bot": False, "first_name": "U"},
        "data": data,
        "message": {
            "message_id": message_id,
            "chat": {"id": user_id if chat_id is None else chat_id, "type": "private"},
        },
    }


def texts(api):
    return [p.get("text", "") for p in api.only("sendMessage")]


def offers_for(api):
    return [json.loads(json.dumps(p)) for p in api.only("sendMessage")]


# ---------------------------------------------------------------------------
# Ссылки и цены
# ---------------------------------------------------------------------------


class UrlTests(unittest.TestCase):
    def test_full_https(self):
        self.assertEqual(
            ob.normalize_gift_url("https://t.me/nft/PlushPepe-123"),
            "https://t.me/nft/PlushPepe-123",
        )

    def test_without_scheme(self):
        self.assertEqual(ob.normalize_gift_url("t.me/nft/Durov-1"), "https://t.me/nft/Durov-1")

    def test_telegram_me(self):
        self.assertEqual(
            ob.normalize_gift_url("telegram.me/nft/Candy-7"), "https://t.me/nft/Candy-7"
        )

    def test_http_upgraded(self):
        self.assertEqual(
            ob.normalize_gift_url("http://t.me/nft/Heart-2"), "https://t.me/nft/Heart-2"
        )

    def test_trailing_slash_and_punctuation(self):
        self.assertEqual(
            ob.normalize_gift_url("https://t.me/nft/Heart-2/,"), "https://t.me/nft/Heart-2"
        )

    def test_collection_number(self):
        self.assertEqual(
            ob.normalize_gift_url("https://t.me/PlushPepe/42"), "https://t.me/PlushPepe/42"
        )

    def test_reject_query(self):
        self.assertIsNone(ob.normalize_gift_url("https://t.me/nft/Heart-2?x=1"))

    def test_reject_foreign_domain(self):
        self.assertIsNone(ob.normalize_gift_url("https://example.com/nft/Heart-2"))

    def test_reject_username_only(self):
        self.assertIsNone(ob.normalize_gift_url("https://t.me/somechannel"))

    def test_reject_empty(self):
        self.assertIsNone(ob.normalize_gift_url(""))

    def test_title_nft(self):
        self.assertEqual(ob.gift_title_from_url("https://t.me/nft/PlushPepe-123"), "PlushPepe 123")

    def test_title_underscore(self):
        self.assertEqual(ob.gift_title_from_url("https://t.me/nft/Durov_Cap-9"), "Durov Cap 9")

    def test_title_collection(self):
        self.assertEqual(ob.gift_title_from_url("https://t.me/PlushPepe/42"), "PlushPepe #42")


class PriceTests(unittest.TestCase):
    def test_parse_offer_text(self):
        url, price = ob.parse_offer_text("/offer https://t.me/nft/PlushPepe-123 250")
        self.assertEqual(url, "https://t.me/nft/PlushPepe-123")
        self.assertEqual(price, 250)

    def test_price_spaces(self):
        _, price = ob.parse_offer_text("/sell t.me/nft/Heart-1 1 000")
        self.assertEqual(price, 1000)

    def test_price_dot(self):
        _, price = ob.parse_offer_text("/offer https://t.me/nft/Heart-1 1.500")
        self.assertEqual(price, 1500)

    def test_price_star(self):
        _, price = ob.parse_offer_text("/offer https://t.me/nft/Heart-1 250⭐")
        self.assertEqual(price, 250)

    def test_price_missing(self):
        url, price = ob.parse_offer_text("/offer https://t.me/nft/Heart-1")
        self.assertEqual(url, "https://t.me/nft/Heart-1")
        self.assertIsNone(price)

    def test_no_url(self):
        url, price = ob.parse_offer_text("/offer 250")
        self.assertIsNone(url)
        self.assertIsNone(price)

    def test_too_many_digits(self):
        _, price = ob.parse_offer_text("/offer https://t.me/nft/Heart-1 1234567890123")
        self.assertIsNone(price)

    def test_digits_inside_url_ignored(self):
        _, price = ob.parse_offer_text("/offer https://t.me/nft/Heart-999")
        self.assertIsNone(price)


class EnvValueTests(unittest.TestCase):
    def test_mask_empty(self):
        self.assertEqual(ob.mask_token(""), "<пусто>")

    def test_mask_short(self):
        self.assertEqual(ob.mask_token("abc"), "abc***")

    def test_mask_long(self):
        masked = ob.mask_token("123456789:AAHabcdefgh")
        self.assertTrue(masked.startswith("12345678***"))
        self.assertNotIn("AAHabcdefgh", masked)

    def test_bool_variants(self):
        for value in ("1", "true", "YES", "on", "да", " y "):
            self.assertTrue(ob.env_bool(value), value)
        for value in ("0", "false", "no", "off", "", None):
            self.assertFalse(ob.env_bool(value), value)

    def test_bool_default(self):
        self.assertTrue(ob.env_bool(None, True))
        self.assertFalse(ob.env_bool(None, False))

    def test_int_fallback(self):
        self.assertEqual(ob.env_int("12", 5), 12)
        self.assertEqual(ob.env_int("oops", 5), 5)
        self.assertEqual(ob.env_int(None, 5), 5)

    def test_float_comma(self):
        self.assertEqual(ob.env_float("1,5", 1.0), 1.5)

    def test_chat_id_number(self):
        self.assertEqual(ob.env_chat_id("-1001234567890"), -1001234567890)

    def test_chat_id_username(self):
        self.assertEqual(ob.env_chat_id("@gift_board"), "@gift_board")
        self.assertEqual(ob.env_chat_id("gift_board"), "@gift_board")

    def test_chat_id_invalid(self):
        self.assertIsNone(ob.env_chat_id("not a chat!"))
        self.assertIsNone(ob.env_chat_id(""))

    def test_error_hint_known(self):
        self.assertIn("уже удалено", ob.error_hint("Bad Request: message to delete not found"))

    def test_error_hint_unknown(self):
        self.assertEqual(ob.error_hint("something else"), "")

    def test_error_hint_edit(self):
        self.assertIn("только свои", ob.error_hint("message can't be edited"))


# ---------------------------------------------------------------------------
# Конфигурация
# ---------------------------------------------------------------------------


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.env = self.dir / ".env"

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, text):
        self.env.write_text(text, encoding="utf-8")

    def test_defaults(self):
        cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.token, "")
        self.assertEqual(cfg.ttl_hours, 24.0)
        self.assertEqual(cfg.min_price, 1)
        self.assertEqual(cfg.max_price, 1_000_000)
        self.assertTrue(cfg.delete_commands)
        self.assertFalse(cfg.dry_run)
        self.assertEqual(cfg.api_base, "https://api.telegram.org")

    def test_env_file_parsing(self):
        self.write(
            "# comment\n"
            "export BOT_TOKEN='123456:AA'\n"
            'API_BASE="https://proxy.example/api"\n'
            "OFFER_TTL_HOURS=1,5\n"
            "\n"
            "DELETE_COMMANDS=no\n"
        )
        cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.token, "123456:AA")
        self.assertEqual(cfg.api_base, "https://proxy.example/api")
        self.assertEqual(cfg.ttl_hours, 1.5)
        self.assertFalse(cfg.delete_commands)

    def test_overrides_win(self):
        self.write("BOT_TOKEN=from-file\n")
        cfg = ob.load_config(str(self.env), overrides={"BOT_TOKEN": "from-arg"})
        self.assertEqual(cfg.token, "from-arg")

    def test_os_env_wins_over_file(self):
        self.write("BOT_TOKEN=from-file\n")
        with mock.patch.dict(os.environ, {"BOT_TOKEN": "from-env"}):
            cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.token, "from-env")

    def test_empty_os_env_falls_back_to_file(self):
        self.write("BOT_TOKEN=from-file\n")
        with mock.patch.dict(os.environ, {"BOT_TOKEN": ""}):
            cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.token, "from-file")

    def test_paths_relative_to_env_dir(self):
        self.write("DB_PATH=data/offers.db\nLOG_FILE=logs/bot.log\n")
        cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.db_path, self.dir.resolve() / "data/offers.db")
        self.assertEqual(cfg.log_file, self.dir.resolve() / "logs/bot.log")

    def test_absolute_paths_untouched(self):
        self.write("DB_PATH=/var/tmp/offers.db\n")
        cfg = ob.load_config(str(self.env), overrides={})
        self.assertEqual(cfg.db_path, Path("/var/tmp/offers.db"))

    def test_clamps(self):
        self.write(
            "OFFER_TTL_HOURS=-5\nMIN_PRICE=0\nMAX_PRICE=0\nMAX_OPEN_OFFERS=0\n"
            "CREATE_COOLDOWN_SECONDS=-1\nPOLL_TIMEOUT=999\n"
        )
        cfg = ob.load_config(str(self.env), overrides={})
        self.assertGreaterEqual(cfg.ttl_hours, 0.1)
        self.assertEqual(cfg.min_price, 1)
        self.assertEqual(cfg.max_price, cfg.min_price)
        self.assertEqual(cfg.max_open_offers, 1)
        self.assertEqual(cfg.create_cooldown, 0.0)
        self.assertEqual(cfg.poll_timeout, 50)

    def test_board_username_and_number(self):
        self.write("BOARD_CHAT_ID=@gift_board\n")
        self.assertEqual(ob.load_config(str(self.env), overrides={}).board_chat_id, "@gift_board")
        self.write("BOARD_CHAT_ID=-100123\n")
        self.assertEqual(ob.load_config(str(self.env), overrides={}).board_chat_id, -100123)

    def test_invalid_board_disabled(self):
        self.write("BOARD_CHAT_ID=борда\n")
        self.assertIsNone(ob.load_config(str(self.env), overrides={}).board_chat_id)

    def test_missing_env_file_is_ok(self):
        cfg = ob.load_config(str(self.dir / "nope.env"), overrides={"BOT_TOKEN": "x"})
        self.assertEqual(cfg.token, "x")

    def test_ttl_seconds(self):
        cfg = make_cfg(ttl_hours=2)
        self.assertEqual(cfg.ttl_seconds, 7200)

    def test_summary_masks_token(self):
        cfg = make_cfg()
        cfg.token = "123456789:AAHsecret"
        summary = cfg.summary()
        self.assertIn("12345678***et", summary)
        self.assertNotIn("AAHsecret", summary)

    def test_log_level_upper(self):
        self.write("LOG_LEVEL=debug\n")
        self.assertEqual(ob.load_config(str(self.env), overrides={}).log_level, "DEBUG")


class SetTokenTests(unittest.TestCase):
    TOKEN = "123456789:" + "A" * 35

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.env = self.dir / ".env"

    def tearDown(self):
        self.tmp.cleanup()

    def test_creates_file_with_600(self):
        code = ob.set_token(self.env, self.TOKEN, verify=False)
        self.assertEqual(code, 0)
        self.assertIn(f"BOT_TOKEN={self.TOKEN}", self.env.read_text(encoding="utf-8"))
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)

    def test_replaces_only_token(self):
        self.env.write_text("BOT_TOKEN=old\nOFFER_TTL_HOURS=5\n", encoding="utf-8")
        ob.set_token(self.env, self.TOKEN, verify=False)
        content = self.env.read_text(encoding="utf-8")
        self.assertIn(f"BOT_TOKEN={self.TOKEN}", content)
        self.assertIn("OFFER_TTL_HOURS=5", content)
        self.assertNotIn("old", content)

    def test_keeps_comments(self):
        self.env.write_text("# мой конфиг\nBOT_TOKEN=\n", encoding="utf-8")
        ob.set_token(self.env, self.TOKEN, verify=False)
        self.assertIn("# мой конфиг", self.env.read_text(encoding="utf-8"))

    def test_invalid_token_rejected(self):
        self.assertEqual(ob.set_token(self.env, "мусор", verify=False), 2)
        self.assertFalse(self.env.exists())

    def test_short_secret_rejected(self):
        self.assertEqual(ob.set_token(self.env, "123:abc", verify=False), 2)

    def test_parse_args_set_token(self):
        args = ob.parse_args(["--set-token", self.TOKEN])
        self.assertEqual(args.set_token, self.TOKEN)


# ---------------------------------------------------------------------------
# База
# ---------------------------------------------------------------------------


class DBTests(unittest.TestCase):
    def setUp(self):
        self.db = ob.DB(":memory:")

    def tearDown(self):
        self.db.close()

    def make(self, **kwargs):
        now = time.time()
        data = dict(
            id=0,
            kind="buy",
            creator_id=1,
            creator_name="A",
            recipient_id=None,
            gift_url="https://t.me/nft/Heart-1",
            gift_title="Heart 1",
            price=100,
            status="open",
            conn_id=None,
            chat_id=1,
            card_chat_id=None,
            card_msg_id=None,
            card_conn_id=None,
            board_chat_id=None,
            board_msg_id=None,
            created_at=now,
            updated_at=now,
        )
        data.update(kwargs)
        return self.db.create(ob.Offer(**data))

    def test_create_get(self):
        offer = self.make()
        self.assertEqual(offer.id, 1)
        loaded = self.db.get(1)
        self.assertEqual(loaded.price, 100)
        self.assertEqual(loaded.gift_title, "Heart 1")

    def test_get_missing(self):
        self.assertIsNone(self.db.get(999))

    def test_update_touches_timestamp(self):
        offer = self.make()
        self.db.update(offer.id, status="accepted")
        loaded = self.db.get(offer.id)
        self.assertEqual(loaded.status, "accepted")
        self.assertGreaterEqual(loaded.updated_at, offer.updated_at)

    def test_update_empty_is_noop(self):
        offer = self.make()
        self.db.update(offer.id)
        self.assertEqual(self.db.get(offer.id).status, "open")

    def test_list_by_creator(self):
        self.make(creator_id=1)
        self.make(creator_id=2)
        self.make(creator_id=1)
        ids = [o.id for o in self.db.list_by_creator(1)]
        self.assertEqual(ids, [3, 1])

    def test_count_open(self):
        self.make(status="open")
        self.make(status="accepted")
        self.make(status="sent")
        self.make(status="completed")
        self.assertEqual(self.db.count_open_by_creator(1), 3)

    def test_expired_cutoff(self):
        old = self.make(created_at=time.time() - 10_000)
        self.make(created_at=time.time())
        found = self.db.expired(time.time() - 3600)
        self.assertEqual([o.id for o in found], [old.id])

    def test_expired_ignores_closed(self):
        self.make(status="completed", created_at=time.time() - 10_000)
        self.assertEqual(self.db.expired(time.time()), [])

    def test_stats(self):
        self.make(status="open")
        self.make(status="open")
        self.make(status="completed")
        self.assertEqual(self.db.stats(), {"open": 2, "completed": 1})

    def test_meta(self):
        self.assertIsNone(self.db.meta_get("offset"))
        self.db.meta_set("offset", "7")
        self.assertEqual(self.db.meta_get("offset"), "7")
        self.db.meta_set("offset", "8")
        self.assertEqual(self.db.meta_get("offset"), "8")

    def test_board_chat_id_kept_as_text(self):
        offer = self.make(board_chat_id="@gift_board", board_msg_id=5)
        loaded = self.db.get(offer.id)
        self.assertEqual(loaded.board_chat_id, "@gift_board")
        self.assertEqual(loaded.board_msg_id, 5)

    def test_persistent_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / "offers.db")
            db = ob.DB(path)
            offer = db.create(
                ob.Offer(
                    id=0, kind="sell", creator_id=5, creator_name="B", recipient_id=None,
                    gift_url="https://t.me/nft/Heart-2", gift_title="Heart 2", price=5,
                    status="open", conn_id=None, chat_id=5, card_chat_id=None,
                    card_msg_id=None, card_conn_id=None, board_chat_id=None,
                    board_msg_id=None, created_at=time.time(), updated_at=time.time(),
                )
            )
            db.close()
            db2 = ob.DB(path)
            self.assertEqual(db2.get(offer.id).kind, "sell")
            db2.close()

    def test_migration_adds_card_conn_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / "old.db")
            conn = sqlite3.connect(path)
            conn.executescript(
                """
                CREATE TABLE offers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    creator_id INTEGER NOT NULL,
                    creator_name TEXT NOT NULL DEFAULT '',
                    recipient_id INTEGER,
                    gift_url TEXT NOT NULL,
                    gift_title TEXT NOT NULL DEFAULT 'Подарок',
                    price INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'open',
                    conn_id TEXT,
                    chat_id INTEGER,
                    card_chat_id INTEGER,
                    card_msg_id INTEGER,
                    board_chat_id TEXT,
                    board_msg_id INTEGER,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
                """
            )
            conn.commit()
            conn.close()
            db = ob.DB(path)
            columns = {row["name"] for row in db.conn.execute("PRAGMA table_info(offers)")}
            self.assertIn("card_conn_id", columns)
            db.close()


# ---------------------------------------------------------------------------
# Тексты
# ---------------------------------------------------------------------------


class TextTests(unittest.TestCase):
    def make_offer(self, **kwargs):
        now = time.time()
        base = dict(
            id=7, kind="buy", creator_id=1, creator_name="Аня", recipient_id=2,
            gift_url="https://t.me/nft/Heart-1", gift_title="Heart 1", price=250,
            status="open", conn_id=None, chat_id=1, card_chat_id=2, card_msg_id=9,
            card_conn_id=None, board_chat_id=None, board_msg_id=None,
            created_at=now, updated_at=now,
        )
        base.update(kwargs)
        return ob.Offer(**base)

    def test_card_contains_key_facts(self):
        text = ob.card_text(self.make_offer(), 24)
        self.assertIn("Heart 1", text)
        self.assertIn("250", text)
        self.assertIn("№7", text)
        self.assertIn("https://t.me/nft/Heart-1", text)
        self.assertIn("1:1", text)

    def test_card_status_lines(self):
        for status, needle in (
            ("accepted", "Намерение принято"),
            ("sent", "отправил официальный оффер"),
            ("completed", "Сделка закрыта"),
            ("declined", "отклонено"),
            ("cancelled", "Отменено"),
            ("expired", "Истекло"),
        ):
            self.assertIn(needle, ob.card_text(self.make_offer(status=status), 24))

    def test_card_escapes_name(self):
        text = ob.card_text(self.make_offer(creator_name="<b>x</b>"), 24)
        self.assertIn("&lt;b&gt;x&lt;/b&gt;", text)
        self.assertNotIn("<b>x</b>", text)

    def test_keyboard_open(self):
        payload = json.dumps(ob.keyboard_for(self.make_offer()))
        self.assertIn("acc:7", payload)
        self.assertIn("dec:7", payload)
        self.assertIn("how:7", payload)

    def test_keyboard_accepted(self):
        payload = json.dumps(ob.keyboard_for(self.make_offer(status="accepted")))
        self.assertIn("sent:7", payload)
        self.assertIn("done:7", payload)
        self.assertIn("cxl:7", payload)
        self.assertNotIn("acc:7", payload)

    def test_keyboard_final(self):
        payload = json.dumps(ob.keyboard_for(self.make_offer(status="completed")))
        self.assertNotIn("acc:7", payload)
        self.assertNotIn("cxl:7", payload)

    def test_buyer_instructions(self):
        text = ob.buyer_instructions(self.make_offer())
        self.assertIn("покупатель", text)
        self.assertIn("Offer to Buy", text)
        self.assertIn("250", text)
        self.assertIn("вернутся", text)

    def test_seller_instructions(self):
        text = ob.seller_instructions(self.make_offer())
        self.assertIn("продавец", text)
        self.assertIn("Ничего не отправляйте", text)
        self.assertIn("Примите оффер", text)

    def test_how_text(self):
        text = ob.how_text("gift_offer_bot")
        self.assertIn("1:1", text)
        self.assertIn("0%", text)
        self.assertIn("Offer to Buy", text)
        self.assertIn("Красные флаги", text)

    def test_status_ru(self):
        self.assertEqual(ob.status_ru("open"), "открыт")
        self.assertEqual(ob.status_ru("weird"), "weird")

    def test_offer_roles(self):
        buy = self.make_offer(kind="buy")
        self.assertEqual((buy.buyer_id, buy.seller_id), (1, 2))
        sell = self.make_offer(kind="sell")
        self.assertEqual((sell.buyer_id, sell.seller_id), (2, 1))

    def test_kind_titles(self):
        self.assertEqual(self.make_offer(kind="buy").kind_title, "Хочу купить")
        self.assertEqual(self.make_offer(kind="sell").kind_title, "Продаю")


# ---------------------------------------------------------------------------
# Сценарий бота
# ---------------------------------------------------------------------------


class BotFlowTests(unittest.TestCase):
    def setUp(self):
        self.bot, self.api, self.db, self.cfg = make_bot()

    def tearDown(self):
        self.db.close()

    # -- создание ---------------------------------------------------------

    def test_offer_deletes_command_first(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 250")))
        methods = self.api.methods()
        self.assertIn("deleteMessage", methods)
        self.assertLess(methods.index("deleteMessage"), methods.index("sendMessage"))

    def test_offer_confirmation_has_deep_link(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 250")))
        text = texts(self.api)[0]
        self.assertIn("https://t.me/gift_offer_bot?start=offer_1", text)
        self.assertIn("250", text)

    def test_offer_saved(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 250")))
        offer = self.db.get(1)
        self.assertEqual(offer.kind, "buy")
        self.assertEqual(offer.status, "open")
        self.assertEqual(offer.price, 250)
        self.assertEqual(offer.gift_title, "Heart 1")

    def test_sell_saved(self):
        self.bot.handle_update(upd(1, dm("/sell https://t.me/nft/Heart-1 250", user_id=5)))
        self.assertEqual(self.db.get(1).kind, "sell")

    def test_offer_rejects_foreign_url(self):
        self.bot.handle_update(upd(1, dm("/offer https://example.com/x 250")))
        self.assertEqual(self.db.stats(), {})
        self.assertIn("Не вижу ссылку", texts(self.api)[0])

    def test_offer_rejects_missing_price(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1")))
        self.assertEqual(self.db.stats(), {})
        self.assertIn("Не вижу цену", texts(self.api)[0])

    def test_offer_rejects_price_below_min(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 0")))
        self.assertEqual(self.db.stats(), {})
        self.assertIn("Цена должна быть", texts(self.api)[0])

    def test_offer_rejects_price_above_max(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 1000001")))
        self.assertEqual(self.db.stats(), {})
        self.assertIn("Цена должна быть", texts(self.api)[0])

    def test_offer_rejects_ton(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100 TON")))
        self.assertEqual(self.db.stats(), {})
        self.assertIn("только звёзды", texts(self.api)[0])

    def test_offer_allows_gift_named_ton(self):
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/TON-1 100")))
        self.assertIsNotNone(self.db.get(1))

    def test_cooldown(self):
        bot, api, db, cfg = make_bot(create_cooldown=60)
        bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        api.clear()
        bot.handle_update(upd(2, dm("/offer https://t.me/nft/Heart-2 100")))
        self.assertEqual(db.count_open_by_creator(100), 1)
        self.assertIn("Слишком часто", texts(api)[0])
        db.close()

    def test_max_open_offers(self):
        bot, api, db, cfg = make_bot(max_open_offers=1)
        bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        api.clear()
        bot.handle_update(upd(2, dm("/offer https://t.me/nft/Heart-2 100")))
        self.assertIn("активных офферов", texts(api)[0])
        self.assertEqual(db.count_open_by_creator(100), 1)
        db.close()

    def test_board_post(self):
        bot, api, db, cfg = make_bot(board_chat_id=-100123)
        bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        offer = db.get(1)
        self.assertEqual(offer.board_chat_id, "-100123")
        self.assertIsNotNone(offer.board_msg_id)
        board_posts = [p for p in api.only("sendMessage") if p.get("chat_id") == -100123]
        self.assertEqual(len(board_posts), 1)
        self.assertIn("start=offer_1", json.dumps(board_posts[0]["reply_markup"]))
        db.close()

    def test_delete_disabled(self):
        bot, api, db, cfg = make_bot(delete_commands=False)
        bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        self.assertNotIn("deleteMessage", api.methods())
        db.close()

    def test_delete_failure_tolerated(self):
        self.api.fail_on["deleteMessage"] = ob.TelegramError("message to delete not found", 400)
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        self.assertIsNotNone(self.db.get(1))

    def test_send_failure_tolerated(self):
        self.api.fail_on["sendMessage"] = ob.TelegramError("bot was blocked by the user", 403)
        self.bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        self.assertIsNotNone(self.db.get(1))
        self.assertEqual(self.db.get(1).status, "open")

    # -- открытие второй стороной ----------------------------------------

    def create(self, text="/offer https://t.me/nft/Heart-1 250", user_id=100, update_id=1):
        self.bot.handle_update(upd(update_id, dm(text, user_id=user_id)))

    def open_by(self, user_id=200, offer_id=1, update_id=2):
        self.bot.handle_update(upd(update_id, dm(f"/start offer_{offer_id}", user_id=user_id, message_id=20)))

    def test_open_shows_card_to_recipient(self):
        self.create()
        self.api.clear()
        self.open_by()
        self.assertEqual(len(self.api.only("sendMessage")), 1)
        card = self.api.only("sendMessage")[0]
        self.assertIn("acc:1", json.dumps(card["reply_markup"]))
        self.assertEqual(self.db.get(1).recipient_id, 200)

    def test_open_by_creator_gets_link_only(self):
        self.create()
        self.api.clear()
        self.open_by(user_id=100)
        self.assertIn("Это ваш оффер", texts(self.api)[0])
        self.assertNotIn("reply_markup", self.api.only("sendMessage")[0])

    def test_open_missing_offer(self):
        self.open_by(offer_id=999)
        self.assertIn("не найден", texts(self.api)[0])

    def test_open_broken_payload(self):
        self.bot.handle_update(upd(1, dm("/start offer_abc")))
        self.assertIn("Битая ссылка", texts(self.api)[0])

    def test_start_greeting(self):
        self.bot.handle_update(upd(1, dm("/start")))
        text = texts(self.api)[0]
        self.assertIn("/offer", text)
        self.assertIn("1:1", text)

    def test_card_shows_creator_name(self):
        self.create(text="/offer https://t.me/nft/Heart-1 250")
        self.api.calls.clear()
        self.bot.handle_update(upd(2, dm("/start offer_1", user_id=200, message_id=21)))
        joined = json.dumps(self.api.calls, ensure_ascii=False)
        self.assertIn("Tester", joined)

    # -- принятие ---------------------------------------------------------

    def accept(self, user_id=200, offer_id=1, update_id=5):
        self.bot.handle_update(upd(update_id, callback=cb(f"acc:{offer_id}", user_id)))

    def test_accept_sets_status_and_instructions(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.accept()
        offer = self.db.get(1)
        self.assertEqual(offer.status, "accepted")
        sent = texts(self.api)
        self.assertTrue(any("Offer to Buy" in t for t in sent), sent)
        self.assertTrue(any("Ничего не отправляйте" in t for t in sent), sent)
        self.assertIn("editMessageText", self.api.methods())

    def test_accept_sends_both_sides(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.accept()
        chats = {p["chat_id"] for p in self.api.only("sendMessage")}
        self.assertEqual(chats, {100, 200})

    def test_accept_by_creator_refused(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.accept(user_id=100)
        self.assertEqual(self.db.get(1).status, "open")
        self.assertEqual(self.api.only("sendMessage"), [])

    def test_accept_twice_refused(self):
        self.create()
        self.open_by()
        self.accept()
        self.api.clear()
        self.accept(update_id=6)
        self.assertIn("Уже", self.api.only("answerCallbackQuery")[0]["text"])

    def test_accept_missing_offer(self):
        self.bot.handle_update(upd(1, callback=cb("acc:999", 200)))
        answer = self.api.only("answerCallbackQuery")[0]
        self.assertTrue(answer["show_alert"])

    def test_accept_sell_offer_buyer_is_recipient(self):
        self.create(text="/sell https://t.me/nft/Heart-1 300", user_id=300)
        self.open_by(user_id=400)
        self.api.clear()
        self.accept(user_id=400)
        offer = self.db.get(1)
        self.assertEqual(offer.buyer_id, 400)
        self.assertEqual(offer.seller_id, 300)
        self.assertTrue(any("покупатель" in t for t in texts(self.api)))

    # -- отправка и завершение -------------------------------------------

    def test_sent_by_seller_refused(self):
        self.create()
        self.open_by()
        self.accept()
        self.api.clear()
        self.bot.handle_update(upd(6, callback=cb("sent:1", 200)))
        self.assertEqual(self.db.get(1).status, "accepted")
        self.assertTrue(self.api.only("answerCallbackQuery")[0]["show_alert"])

    def test_sent_by_buyer(self):
        self.create()
        self.open_by()
        self.accept()
        self.api.clear()
        self.bot.handle_update(upd(6, callback=cb("sent:1", 100)))
        self.assertEqual(self.db.get(1).status, "sent")
        self.assertTrue(any("Покупатель отправил" in t for t in texts(self.api)))

    def test_done_by_buyer_refused(self):
        self.create()
        self.open_by()
        self.accept()
        self.bot.handle_update(upd(6, callback=cb("sent:1", 100)))
        self.api.clear()
        self.bot.handle_update(upd(7, callback=cb("done:1", 100)))
        self.assertEqual(self.db.get(1).status, "sent")

    def test_done_by_seller(self):
        self.create()
        self.open_by()
        self.accept()
        self.bot.handle_update(upd(6, callback=cb("sent:1", 100)))
        self.api.clear()
        self.bot.handle_update(upd(7, callback=cb("done:1", 200)))
        self.assertEqual(self.db.get(1).status, "completed")
        self.assertTrue(any("подтвердил сделку" in t for t in texts(self.api)))

    def test_done_without_sent_allowed(self):
        self.create()
        self.open_by()
        self.accept()
        self.bot.handle_update(upd(6, callback=cb("done:1", 200)))
        self.assertEqual(self.db.get(1).status, "completed")

    # -- отмена -----------------------------------------------------------

    def test_cancel_button_by_stranger(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.bot.handle_update(upd(6, callback=cb("cxl:1", 999)))
        self.assertEqual(self.db.get(1).status, "open")
        self.assertIn("только автор", self.api.only("answerCallbackQuery")[0]["text"])

    def test_cancel_button_by_creator(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.bot.handle_update(upd(6, callback=cb("cxl:1", 100)))
        self.assertEqual(self.db.get(1).status, "cancelled")
        self.assertTrue(any("Автор отменил" in t for t in texts(self.api)))

    def test_cancel_command(self):
        self.create()
        self.api.clear()
        self.bot.handle_update(upd(3, dm("/cancel 1", message_id=30)))
        self.assertEqual(self.db.get(1).status, "cancelled")
        self.assertIn("отменён", texts(self.api)[0])

    def test_cancel_command_foreign(self):
        self.create()
        self.api.clear()
        self.bot.handle_update(upd(3, dm("/cancel 1", user_id=777, message_id=30)))
        self.assertEqual(self.db.get(1).status, "open")
        self.assertIn("не найден", texts(self.api)[0])

    def test_cancel_command_needs_id(self):
        self.bot.handle_update(upd(1, dm("/cancel")))
        self.assertIn("Укажите номер", texts(self.api)[0])

    def test_cancel_closed_offer(self):
        self.create()
        self.db.update(1, status="completed")
        self.api.clear()
        self.bot.handle_update(upd(2, dm("/cancel 1")))
        self.assertIn("Уже закрыт", texts(self.api)[0])

    # -- прочее -----------------------------------------------------------

    def test_decline(self):
        self.create()
        self.open_by()
        self.api.clear()
        self.bot.handle_update(upd(6, callback=cb("dec:1", 200)))
        self.assertEqual(self.db.get(1).status, "declined")
        self.assertTrue(any("отклонила" in t for t in texts(self.api)))

    def test_decline_by_creator_refused(self):
        self.create()
        self.open_by()
        self.bot.handle_update(upd(6, callback=cb("dec:1", 100)))
        self.assertEqual(self.db.get(1).status, "open")

    def test_how_button(self):
        self.create()
        self.api.clear()
        self.bot.handle_update(upd(2, callback=cb("how:1", 200)))
        self.assertTrue(any("Как проходит сделка" in t for t in texts(self.api)))

    def test_unknown_callback_action(self):
        self.create()
        self.api.clear()
        self.bot.handle_update(upd(2, callback=cb("zzz:1", 200)))
        self.assertIn("Неизвестное действие", self.api.only("answerCallbackQuery")[0]["text"])

    def test_garbage_callback(self):
        self.bot.handle_update(upd(1, callback={"id": "x", "from": {"id": 1}, "data": "nope"}))
        self.assertIn("устарела", self.api.only("answerCallbackQuery")[0]["text"])

    def test_how_command(self):
        self.bot.handle_update(upd(1, dm("/how")))
        self.assertIn("Offer to Buy", texts(self.api)[0])

    def test_help_command(self):
        self.bot.handle_update(upd(1, dm("/help")))
        text = texts(self.api)[0]
        self.assertIn("/offer", text)
        self.assertIn("0%", text)

    def test_id_command(self):
        self.bot.handle_update(upd(1, dm("/id", user_id=4242)))
        text = texts(self.api)[0]
        self.assertIn("4242", text)
        self.assertIn("chat_id", text)

    def test_myoffers_empty(self):
        self.bot.handle_update(upd(1, dm("/myoffers")))
        self.assertIn("пока нет офферов", texts(self.api)[0])

    def test_myoffers_list(self):
        self.create()
        self.api.clear()
        self.bot.handle_update(upd(2, dm("/myoffers")))
        text = texts(self.api)[0]
        self.assertIn("№1", text)
        self.assertIn("ссылка для второй стороны", text)

    def test_command_with_username_suffix(self):
        self.bot.handle_update(upd(1, dm("/offer@gift_offer_bot https://t.me/nft/Heart-1 250")))
        self.assertIsNotNone(self.db.get(1))

    def test_ignores_non_command(self):
        self.bot.handle_update(upd(1, dm("привет")))
        self.assertEqual(self.api.calls, [])

    def test_ignores_bots(self):
        message = dm("/offer https://t.me/nft/Heart-1 250")
        message["from"]["is_bot"] = True
        self.bot.handle_update(upd(1, message))
        self.assertEqual(self.api.calls, [])

    def test_ignores_unknown_update(self):
        self.bot.handle_update({"update_id": 1, "edited_message": {}})
        self.assertEqual(self.api.calls, [])

    def test_business_connection_logged(self):
        self.bot.handle_update(
            {
                "update_id": 2,
                "business_connection": {
                    "id": "bc1",
                    "is_enabled": True,
                    "rights": {"can_reply": True, "can_delete_all_messages": True},
                },
            }
        )
        self.assertEqual(self.api.calls, [])

    def test_business_message_deletes_via_business_api(self):
        message = dm("/offer https://t.me/nft/Heart-1 100")
        message["business_connection_id"] = "bc1"
        self.bot.handle_update({"update_id": 1, "business_message": message})
        self.assertIn("deleteBusinessMessages", self.api.methods())
        self.assertEqual(self.db.get(1).conn_id, "bc1")

    def test_card_edit_uses_card_connection(self):
        self.create()
        self.open_by()
        self.accept()
        call = self.api.only("editMessageText")[0]
        self.assertNotIn("business_connection_id", call)

    def test_expire(self):
        self.create()
        self.open_by()
        self.db.update(1, created_at=time.time() - 100 * 3600)
        self.api.clear()
        self.assertEqual(self.bot.expire_stale(), 1)
        self.assertEqual(self.db.get(1).status, "expired")
        self.assertIn("editMessageText", self.api.methods())
        self.assertTrue(any("истёк" in t for t in texts(self.api)))

    def test_expire_notify_disabled(self):
        self.create()
        self.open_by()
        self.db.update(1, created_at=time.time() - 100 * 3600)
        self.bot.cfg.notify_on_expire = False
        self.api.clear()
        self.bot.expire_stale()
        self.assertEqual(texts(self.api), [])
        self.assertEqual(self.db.get(1).status, "expired")

    def test_expire_fresh_nothing(self):
        self.create()
        self.assertEqual(self.bot.expire_stale(), 0)

    def test_handle_update_swallows_errors(self):
        with mock.patch.object(self.bot, "_handle_update", side_effect=RuntimeError("boom")):
            self.bot.handle_update({"update_id": 1})

    def test_poll_once_offset_persisted(self):
        updates = [upd(41, dm("/how", message_id=50)), upd(42, dm("/help", message_id=51))]
        with mock.patch.object(self.bot.api, "get_updates", return_value=updates):
            count = self.bot.poll_once(timeout=0)
        self.assertEqual(count, 2)
        self.assertEqual(self.bot.offset, 43)
        self.assertEqual(self.db.meta_get("offset"), "43")

    def test_poll_once_empty(self):
        with mock.patch.object(self.bot.api, "get_updates", return_value=[]):
            self.assertEqual(self.bot.poll_once(timeout=0), 0)

    def test_edit_disabled_keeps_card(self):
        bot, api, db, cfg = make_bot(edit_cards=False)
        bot.handle_update(upd(1, dm("/offer https://t.me/nft/Heart-1 100")))
        bot.handle_update(upd(2, dm("/start offer_1", user_id=200, message_id=20)))
        api.clear()
        bot.handle_update(upd(3, callback=cb("acc:1", 200)))
        self.assertEqual(db.get(1).status, "accepted")
        self.assertNotIn("editMessageText", api.methods())
        db.close()


# ---------------------------------------------------------------------------
# API-клиент
# ---------------------------------------------------------------------------


class FakeApiTests(unittest.TestCase):
    def test_fake_records(self):
        api = ob.FakeTelegramAPI()
        response = api.send_message(1, "hi")
        self.assertEqual(api.methods(), ["sendMessage"])
        self.assertEqual(api.only("sendMessage")[0]["text"], "hi")
        self.assertIn("message_id", response)

    def test_dry_run(self):
        api = ob.FakeTelegramAPI()
        api.dry_run = True
        self.assertTrue(api.send_message(1, "hi"))
        self.assertEqual(len(api.only("sendMessage")), 1)  # dry-run только записывает вызов

    def test_fail_on(self):
        api = ob.FakeTelegramAPI(fail_on={"getMe": ob.TelegramError("Unauthorized", 401)})
        with self.assertRaises(ob.TelegramError):
            api.get_me()

    def test_telegram_error_str(self):
        err = ob.TelegramError("Bad Request", 400, method="sendMessage")
        self.assertIn("sendMessage", str(err))
        self.assertIn("400", str(err))

    def test_parse_error(self):
        desc, retry = ob.TelegramAPI._parse_error(
            '{"ok":false,"description":"Too Many Requests","parameters":{"retry_after":7}}', 429
        )
        self.assertEqual(desc, "Too Many Requests")
        self.assertEqual(retry, 7.0)

    def test_parse_error_bad_json(self):
        desc, retry = ob.TelegramAPI._parse_error("<html>", 500)
        self.assertEqual(desc, "<html>")
        self.assertIsNone(retry)


if __name__ == "__main__":
    unittest.main(verbosity=2)

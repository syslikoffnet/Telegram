#!/usr/bin/env python3
"""Behavior contract for local-only archive transitions.

Exercises a small persistent SQLite model of the app's storage contract: server
kick/delete/revocation retain local snapshots, process restart keeps the account
offline/read-only, while an explicit account reset still purges its slot. The
companion test-pengram-local-keep.py verifies the Java event-to-storage wiring.
"""

import sqlite3


class LocalCopyHarness:
    def __init__(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE prefs(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE dialogs(account INTEGER, dialog_id INTEGER, body TEXT,
                                 PRIMARY KEY(account, dialog_id));
            CREATE TABLE history(account INTEGER, dialog_id INTEGER, message_id INTEGER,
                                 action TEXT, data TEXT,
                                 PRIMARY KEY(account, dialog_id, message_id, action));
            CREATE TABLE deleted_marks(account INTEGER, dialog_id INTEGER, message_id INTEGER,
                                       PRIMARY KEY(account, dialog_id, message_id));
        """)
        self.account = 2
        self.user_id = 9001
        self.local_keep = True
        self.network_online = True
        self.read_only = False

    def cache_dialog(self, dialog_id, text):
        self.db.execute("INSERT OR REPLACE INTO dialogs VALUES(?,?,?)",
                        (self.account, dialog_id, text))
        self.db.commit()

    def server_kick(self, dialog_id):
        row = self.db.execute("SELECT body FROM dialogs WHERE account=? AND dialog_id=?",
                              (self.account, dialog_id)).fetchone()
        if self.local_keep and row:
            self.db.execute("INSERT OR IGNORE INTO history VALUES(?,?,?,?,?)",
                            (self.account, dialog_id, 0, "saved", row[0]))
        elif row:
            self.db.execute("DELETE FROM dialogs WHERE account=? AND dialog_id=?",
                            (self.account, dialog_id))
        self.db.commit()

    def server_delete_message(self, dialog_id, message_id, before_image):
        if self.local_keep:
            self.db.execute("INSERT OR IGNORE INTO history VALUES(?,?,?,?,?)",
                            (self.account, dialog_id, message_id, "deleted", before_image))
            self.db.execute("INSERT OR IGNORE INTO deleted_marks VALUES(?,?,?)",
                            (self.account, dialog_id, message_id))
        else:
            self.db.execute("DELETE FROM dialogs WHERE account=? AND dialog_id=?",
                            (self.account, dialog_id))
        self.db.commit()

    def session_revoked(self):
        if self.local_keep:
            self.db.execute("INSERT OR REPLACE INTO prefs VALUES(?,?)",
                            (f"localOnlyRevokedAccount_{self.account}", "true"))
            self.network_online = False
            self.read_only = True
        else:
            self.clear_account_slot()
        self.db.commit()

    def restart(self):
        self.network_online = False
        self.read_only = False
        row = self.db.execute("SELECT value FROM prefs WHERE key=?",
                              (f"localOnlyRevokedAccount_{self.account}",)).fetchone()
        if row and row[0] == "true":
            self.network_online = False
            self.read_only = True

    def clear_account_slot(self):
        for table in ("dialogs", "history", "deleted_marks"):
            self.db.execute(f"DELETE FROM {table} WHERE account=?", (self.account,))
        self.db.execute("DELETE FROM prefs WHERE key=?",
                        (f"localOnlyRevokedAccount_{self.account}",))
        self.user_id = 0
        self.network_online = True
        self.read_only = False
        self.db.commit()


def main():
    app = LocalCopyHarness()
    app.cache_dialog(-777, "cached conversation")
    app.server_kick(-777)
    assert app.db.execute("SELECT body FROM dialogs WHERE account=2 AND dialog_id=-777").fetchone()[0] == "cached conversation"
    assert app.db.execute("SELECT data FROM history WHERE account=2 AND dialog_id=-777 AND action='saved'").fetchone()[0] == "cached conversation"

    app.server_delete_message(-777, 41, "before deletion")
    assert app.db.execute("SELECT data FROM history WHERE account=2 AND dialog_id=-777 AND message_id=41 AND action='deleted'").fetchone()[0] == "before deletion"
    assert app.db.execute("SELECT 1 FROM deleted_marks WHERE account=2 AND dialog_id=-777 AND message_id=41").fetchone()

    app.session_revoked()
    assert app.user_id == 9001 and not app.network_online and app.read_only
    app.restart()
    assert app.user_id == 9001 and not app.network_online and app.read_only
    assert app.db.execute("SELECT COUNT(*) FROM history WHERE account=2").fetchone()[0] == 2

    # Explicit account reset is intentionally distinct from server revocation.
    app.clear_account_slot()
    assert app.user_id == 0
    assert app.db.execute("SELECT COUNT(*) FROM history WHERE account=2").fetchone()[0] == 0
    assert app.db.execute("SELECT COUNT(*) FROM dialogs WHERE account=2").fetchone()[0] == 0
    print("behavior: kick, deleted snapshot/mark, revoked-session restart and explicit slot reset: OK")


if __name__ == "__main__":
    main()

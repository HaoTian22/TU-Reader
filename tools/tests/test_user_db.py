"""Exercise the actual v1→v6 migrations SQL against exported Room schemas."""

import json
from pathlib import Path
import re
import sqlite3
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/com.example.nfctransit.data.db.UserDatabase"


def create_schema(db, version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    for entity in schema["entities"]:
        table = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity["indices"]:
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))
    return schema


def migrate(db, start_version=4):
    source = (ROOT / "app/src/main/java/com/example/nfctransit/data/db/UserDatabase.kt").read_text(encoding="utf-8")
    literal = r'(?:""".*?"""|"[^"\n]*")'
    for version in range(start_version, 6):
        migration = source.split(f"private val MIGRATION_{version}_{version + 1} =", 1)[1]
        migration = migration.split("private val MIGRATION_", 1)[0].split("/** 全部迁移", 1)[0]
        statements = re.findall(r'db\.execSQL\(\s*(' + literal + r'(?:\s*\+\s*' + literal + r')*)\s*\)',
                                migration, re.DOTALL)
        assert len(statements) == migration.count("db.execSQL("), "Every migration statement must be exercised"
        for statement in statements:
            sql = "".join(triple or single for triple, single in
                          re.findall(r'"""(.*?)"""|"([^"\n]*)"', statement, re.DOTALL))
            db.execute(sql)


class UserDatabaseMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        create_schema(self.db, 4)
        for card_id in ("card-a", "card-b"):
            self.db.execute(
                "INSERT INTO cards VALUES (?, ?, NULL, 'test', 'TU', '1234', 0, 0, NULL, 1, 1)",
                (card_id, card_id),
            )
        self.db.execute("INSERT INTO raw_records VALUES (1, 'card-a', '0x18', 1, 'TU', 'raw', 'hash', 1, 2)")
        self.db.execute("INSERT INTO transactions_archive VALUES (1, 'card-a', '0x18', 'TU', 'archive', 'hash', '2026-10-06', 100, 1, 2)")
        self.rows = [
            (1, "card-a", 300, "AID-1", "latest", 300, "balance"),
            (2, "card-a", 100, "AID-1", "old", 100, "old-balance"),
            (3, "card-a", 300, "AID-1", "latest-tie", None, None),
            (4, "card-a", 200, "AID-2", "other-app", 200, "other-balance"),
            (5, "card-b", 100, "AID-1", "other-card", 100, "other-balance"),
            (6, "card-a", 200, "AID-3", "newer-time", 0, "zero-balance"),
            (7, "card-a", 100, "AID-3", "higher-row-id", 500, "old-balance"),
        ]
        self.db.executemany("INSERT INTO card_app VALUES (?, ?, ?, ?, ?, ?, ?)", self.rows)

    def test_duplicates_keep_latest_complete_snapshot_and_other_data(self):
        untouched = {table: self.db.execute(f"SELECT * FROM {table}").fetchall()
                     for table in ("cards", "transactions_archive")}
        migrate(self.db)
        self.assertEqual(self.db.execute("SELECT * FROM card_app ORDER BY row_id").fetchall(), self.rows[2:6])
        for table, rows in untouched.items():
            if table == "transactions_archive":
                rows = [row + (None,) for row in rows]
            self.assertEqual(self.db.execute(f"SELECT * FROM {table}").fetchall(), rows)
        self.assertEqual(self.db.execute("SELECT * FROM raw_records").fetchall(),
                         [(1, "card-a", "0x18", 1, "A000000632010105", "raw", "hash", 1, 2)])
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone(), ("ok",))

    def test_migrated_schema_matches_room_and_enforces_unique_key(self):
        migrate(self.db)
        with sqlite3.connect(":memory:") as expected:
            schema = create_schema(expected, 6)
            for entity in schema["entities"]:
                table = entity["tableName"]
                self.assertEqual(self.db.execute(f"PRAGMA table_info({table})").fetchall(),
                                 expected.execute(f"PRAGMA table_info({table})").fetchall())
                self.assertEqual(sorted(self.db.execute(f"PRAGMA index_list({table})")),
                                 sorted(expected.execute(f"PRAGMA index_list({table})")))
                for index in entity["indices"]:
                    name = index["name"]
                    self.assertEqual(self.db.execute(f"PRAGMA index_info({name})").fetchall(),
                                     expected.execute(f"PRAGMA index_info({name})").fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO card_app VALUES (99, 'card-a', 400, 'AID-1', 'duplicate', NULL, NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO raw_records VALUES (99, 'card-a', '0x18', 1, 'A000000632010105', 'duplicate', 'hash', 3, 4)")
        # A different AID with the same SFI/record number remains a separate slot.
        self.db.execute("INSERT INTO raw_records VALUES (99, 'card-a', '0x18', 1, 'A000000632010106', 'alternate', 'hash', 3, 4)")
        self.db.execute("DELETE FROM cards WHERE card_id='card-a'")
        self.assertEqual(self.db.execute("SELECT card_id FROM card_app").fetchall(), [("card-b",)])
        self.assertEqual(self.db.execute("SELECT * FROM raw_records").fetchall(), [])

    def test_legacy_protocols_map_to_application_aids(self):
        self.db.execute("DELETE FROM raw_records")
        samples = [
            ("LNT", "0x15", "5041592E41505059"),
            ("LNT", "0x08", "5041592E41505059"),
            ("LNT", "0x18", "5041592E5449434C"),
            ("CU", "0x18", "A00000000386980701"),
            ("SZT", "0x18", "5041592E535A54"),
            ("SUXIN", "0x18", "535558494E2E4444463031"),
            ("SZTK", "0x18", "535A504B5F5A5959"),
            ("TFT", "0x18", "D156000015B9ABB9B2D3A6D3C3"),
        ]
        for i, (protocol, sfi, _) in enumerate(samples, 10):
            self.db.execute("INSERT INTO raw_records VALUES (?, 'card-a', ?, ?, ?, 'raw', 'hash', 1, 2)",
                            (i, sfi, i, protocol))
        migrate(self.db)
        self.assertEqual([row[0] for row in self.db.execute("SELECT selected_aid FROM raw_records ORDER BY row_id")],
                         [aid for _, _, aid in samples])

    def test_tu_uses_snapshot_before_slot_read_and_coalesces_blank_protocol(self):
        self.db.executemany("INSERT INTO card_app VALUES (?, 'card-a', ?, ?, 'select', NULL, NULL)", [
            (10, 100, "A000000632010106"), (11, 200, "A000000632010105"),
        ])
        self.db.execute("DELETE FROM raw_records")
        self.db.executemany("INSERT INTO raw_records VALUES (?, 'card-a', '0x18', 1, ?, ?, 'hash', ?, 150)", [
            (10, "TU", "older-row", 10), (11, "", "latest-row", 20),
        ])
        migrate(self.db)
        self.assertEqual(self.db.execute("SELECT * FROM raw_records").fetchall(),
                         [(11, "card-a", "0x18", 1, "A000000632010106", "latest-row", "hash", 10, 150)])

    def test_blank_protocol_uses_card_type(self):
        self.db.execute("UPDATE cards SET card_type='YCT' WHERE card_id='card-a'")
        self.db.execute("UPDATE raw_records SET protocol='' WHERE card_id='card-a'")
        migrate(self.db)
        self.assertEqual(self.db.execute("SELECT selected_aid FROM raw_records").fetchone(), ("5041592E5449434C",))

    def test_empty_database_can_migrate(self):
        self.db.execute("DELETE FROM raw_records")
        self.db.execute("DELETE FROM card_app")
        migrate(self.db)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM card_app").fetchone(), (0,))

    def test_all_old_schema_versions_can_reach_current_schema(self):
        with sqlite3.connect(":memory:") as expected:
            schema = create_schema(expected, 6)
            for version in range(1, 6):
                with self.subTest(version=version), sqlite3.connect(":memory:") as db:
                    create_schema(db, version)
                    migrate(db, version)
                    for entity in schema["entities"]:
                        table = entity["tableName"]
                        self.assertEqual(db.execute(f"PRAGMA table_info({table})").fetchall(),
                                         expected.execute(f"PRAGMA table_info({table})").fetchall())
                        self.assertEqual(sorted(db.execute(f"PRAGMA index_list({table})")),
                                         sorted(expected.execute(f"PRAGMA index_list({table})")))


if __name__ == "__main__":
    unittest.main()

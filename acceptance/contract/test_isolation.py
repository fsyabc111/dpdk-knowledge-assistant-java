"""无库负例：证明配置或身份失败时不会执行 CREATE/DROP。"""
import tempfile
import unittest
from pathlib import Path
from acceptance.contract.isolation import PgIsolation, IsolationError, OwnedDatabase

class Connection:
    def __init__(self, ledger, user="test_role", directory="/isolated/pg", database="postgres"):
        self.ledger, self.user, self.directory, self.database = ledger, user, directory, database
        self.answer = None
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, sql, params=()):
        self.ledger.append((sql,params))
        self.answer = (self.user, self.database, self.directory) if sql.startswith("SELECT current_user") else None
        return self
    def fetchone(self): return self.answer

class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.env={"DPDK_TEST_PG_URL":"jdbc:postgresql://127.0.0.1:55481/postgres", "DPDK_TEST_PG_USER":"test_role", "DPDK_TEST_PG_PASSWORD":"runtime-only", "DPDK_TEST_PG_DATA_DIR":"/isolated/pg"}
        self.ledger=[]
    def helper(self, **kwargs):
        connect=lambda **kw: Connection(self.ledger,database=kw["dbname"],**kwargs)
        return PgIsolation.from_env(Path(self.tmp.name)/"manifest.json", env=self.env, connect=connect)
    def test_all_four_required_no_connection(self):
        for field in PgIsolation.REQUIRED:
            with self.subTest(field=field):
                env={**self.env};del env[field]
                with self.assertRaises(IsolationError):PgIsolation.from_env(Path(self.tmp.name)/"manifest.json",env=env)
        self.assertEqual([],self.ledger)
    def test_wrong_user_zero_writes(self):
        with self.assertRaises(IsolationError):self.helper(user="wrong").create("candidate")
        self.assertTrue(all(s.startswith("SELECT") for s,_ in self.ledger))
    def test_sibling_data_dir_rejected_zero_writes(self):
        with self.assertRaises(IsolationError):self.helper(directory="/isolated/pg_evil").create("candidate")
        self.assertTrue(all(s.startswith("SELECT") for s,_ in self.ledger))
    def test_unknown_database_cannot_drop(self):
        h=self.helper()
        with self.assertRaises(IsolationError):h.drop(OwnedDatabase("p0_python_oracle","postgresql://x","x",h.manifest["round_id"]))
        self.assertEqual([],self.ledger)
    def test_ambient_url_and_unsafe_labels_rejected(self):
        for bad in ["postgresql://127.0.0.1/postgres","postgresql://prod.example:5432/postgres","postgresql://127.0.0.1:55481/p0_python_oracle","postgresql://u:secret@127.0.0.1:55481/postgres"]:
            with self.subTest(url=bad):
                with self.assertRaises(IsolationError):PgIsolation.from_env(Path(self.tmp.name)/"m", env={**self.env,"DPDK_TEST_PG_URL":bad})
        h=self.helper()
        for label in ["x;DROP", "../x", "x'", "X", "a"*25]:
            with self.assertRaises(IsolationError):h.create(label)
        self.assertEqual([],self.ledger)

if __name__ == "__main__":unittest.main()

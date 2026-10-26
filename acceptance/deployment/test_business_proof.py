"""无Docker/PG的恢复证明拒绝反例；SQL实际执行另待隔离窗口。"""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).parent))
from business_proof import VERSION,assert_retained_equal,proof_sql,parse_proof
from docker_pg import DockerPg
from guard import GuardError


def row_digest(rows):
    hashes=sorted(hashlib.sha256(json.dumps(row,sort_keys=True,ensure_ascii=False).encode()).hexdigest() for row in rows)
    return hashlib.sha256(''.join(hashes).encode()).hexdigest()


def fixture():
    tables={'grading_submissions':{'rows':1,'row_sha256':row_digest([{'id':'synthetic','score':80}]),'column_schema_sha256':'a'*64,'data_excluded':False},
            'rag_task_inputs':{'rows':1,'row_sha256':None,'column_schema_sha256':'b'*64,'data_excluded':True},
            'unlisted_new_business_table':{'rows':0,'row_sha256':row_digest([]),'column_schema_sha256':'c'*64,'data_excluded':False}}
    return {'version':VERSION,'inventory':sorted(tables),'tables':tables}


class BusinessProofTest(unittest.TestCase):
    def restored(self):
        before=fixture();after=copy.deepcopy(before);after['tables']['rag_task_inputs']['rows']=0
        return before,after
    def test_same_count_changed_grade_is_rejected(self):
        before,after=self.restored()
        after['tables']['grading_submissions']['row_sha256']=row_digest([{'id':'synthetic','score':81}])
        self.assertEqual(before['tables']['grading_submissions']['rows'],after['tables']['grading_submissions']['rows'])
        with self.assertRaisesRegex(GuardError,'完整行变化：grading_submissions'):assert_retained_equal(before,after)
    def test_all_retained_tables_and_empty_tables_are_in_proof(self):
        before,after=self.restored();result=assert_retained_equal(before,after)
        self.assertEqual(result['retained_public_tables'],2);self.assertTrue(result['retained_full_row_multisets_equal'])
        after['tables'].pop('unlisted_new_business_table')
        with self.assertRaises(GuardError):assert_retained_equal(before,after)
    def test_temporary_data_is_excluded_but_structure_and_empty_restore_are_required(self):
        before,after=self.restored();self.assertEqual(assert_retained_equal(before,after)['temporary_input_rows_restored'],0)
        after['tables']['rag_task_inputs']['rows']=1
        with self.assertRaisesRegex(GuardError,'临时输入数据泄漏'):assert_retained_equal(before,after)
        after['tables']['rag_task_inputs']['rows']=0;after['tables']['rag_task_inputs']['column_schema_sha256']='d'*64
        with self.assertRaisesRegex(GuardError,'列结构变化'):assert_retained_equal(before,after)
    def test_order_and_duplicate_rows_have_multiset_semantics(self):
        self.assertEqual(row_digest([{'a':1},{'a':2}]),row_digest([{'a':2},{'a':1}]))
        self.assertNotEqual(row_digest([{'a':1},{'a':1}]),row_digest([{'a':1}]))
    def test_generated_sql_uses_full_rows_stable_order_and_readonly_snapshot(self):
        sql=proof_sql(fixture()['inventory'])
        self.assertIn('REPEATABLE READ READ ONLY',sql);self.assertIn("TIME ZONE 'UTC'",sql)
        self.assertIn('to_jsonb(t)::text',sql);self.assertIn('sha256',sql);self.assertIn('ORDER BY h COLLATE "C"',sql)
        self.assertIn('public."unlisted_new_business_table"',sql)
        self.assertNotIn('FROM public."rag_task_inputs" t',sql)
        self.assertNotIn('SELECT *',sql)
        for tables in (['users'],['rag_task_inputs','users','users'],['rag_task_inputs','evil;DROP']):
            with self.assertRaises(GuardError):proof_sql(tables)
    def test_inventory_change_malformed_digest_and_sensitive_rows_are_not_accepted(self):
        before=fixture();before['inventory'].append('new_table')
        with self.assertRaises(GuardError):parse_proof(json.dumps(before),fixture()['inventory'])
        before=fixture();before['tables']['grading_submissions']['row_sha256']='raw score80 secret'
        with self.assertRaises(GuardError):parse_proof(json.dumps(before),fixture()['inventory'])
    def test_owned_guard_runs_before_any_snapshot_sql(self):
        pg=DockerPg.__new__(DockerPg)
        pg.verify_owned=lambda name:(_ for _ in ()).throw(GuardError('wrong identity'))
        pg._sql=lambda *args:self.fail('SQL before identity')
        with self.assertRaises(GuardError):pg.business_proof('legacy')
    def test_reader_returns_only_hashes_counts_and_structure_markers(self):
        pg=DockerPg.__new__(DockerPg);pg.verify_owned=lambda name:None;calls=[]
        def sql(name,statement):
            calls.append(statement)
            return '\n'.join(fixture()['inventory']) if len(calls)==1 else 'BEGIN\nSET\nSET\n'+json.dumps(fixture())+'\nCOMMIT'
        pg._sql=sql;actual=pg.business_proof('owned')
        self.assertEqual(actual,fixture());self.assertEqual(len(calls),2)
        self.assertNotIn('synthetic',json.dumps(actual));self.assertNotIn('score',json.dumps(actual))

if __name__=='__main__':unittest.main()

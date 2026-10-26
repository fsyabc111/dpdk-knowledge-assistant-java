#!/usr/bin/env python3
"""显式独立native PG的摘要SQL正反例；不是Docker/dump/restore演练。仅授权窗口执行。"""
from pathlib import Path
import argparse
import json
import os
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'contract'))
from isolation import PgIsolation
from business_proof import proof_sql,parse_proof,assert_retained_equal
from guard import GuardError


def run(output):
    output=Path(output).resolve()
    if output.exists():raise GuardError('只允许全新证明输出路径')
    isolation=PgIsolation.from_env(output.with_suffix('.owned.json'),allowed_prefixes=('p5_deployment_',))
    database=isolation.create('proofsql')
    tables=['grading_submissions','rag_task_inputs','synthetic_new_table']
    def snapshot():
        isolation.verify_owned(database)
        with isolation._connect(database.name) as connection:
            cursor=connection.execute(proof_sql(tables));result=None
            while True:
                if cursor.description is not None:
                    row=cursor.fetchone()
                    if result is not None:raise GuardError('重复证明结果')
                    result=row[0]
                if not cursor.nextset():break
        return parse_proof(json.dumps(result),tables)
    try:
        isolation.verify_owned(database)
        with isolation._connect(database.name) as connection:
            connection.execute('CREATE TABLE grading_submissions(id text,obsolete text,score integer); ALTER TABLE grading_submissions DROP COLUMN obsolete; CREATE TABLE rag_task_inputs(task_id text,source_text text); CREATE TABLE synthetic_new_table(id text,value jsonb);')
            connection.execute("INSERT INTO grading_submissions VALUES ('synthetic1',80),('synthetic2',90); INSERT INTO rag_task_inputs VALUES ('synthetic','J5_BACKUP_SOURCE_SENTINEL_819ac'); INSERT INTO synthetic_new_table VALUES ('synthetic','{\"fixed\":true}');")
        before=snapshot()
        isolation.verify_owned(database)
        with isolation._connect(database.name) as connection:connection.execute('DELETE FROM rag_task_inputs')
        after=snapshot();proof=assert_retained_equal(before,after)
        isolation.verify_owned(database)
        with isolation._connect(database.name) as connection:connection.execute("UPDATE grading_submissions SET score=81 WHERE id='synthetic1'")
        changed=snapshot();rejected=False
        try:assert_retained_equal(before,changed)
        except GuardError:rejected=True
        if not rejected:raise AssertionError('same count wrong grade accepted')
        result={'status':'passed','domain':'native_owned_PG_generated_proof_SQL_only_not_dump_restore','identity_verified_before_writes':True,'source':before,'synthetic_excluded_input_target':after,'proof':proof,'same_count_wrong_grade_rejected':True,'docker_or_stack_started':False}
        fd=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
        print('PASS: owned native PG complete-row digest SQL; retained equal/temp excluded/same-count wrong-grade rejected; not dump/restore')
    finally:isolation.drop(database)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    run(parser.parse_args().output)

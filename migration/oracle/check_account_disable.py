"""正式长测结束后的独立补验：有效账户token停用失效，安全重新启用也不复活。"""
from __future__ import annotations
import argparse
import asyncio
import json
from .safety import ROOT,configure,BASELINE


async def execute(database):
    identity=configure(database)
    from app.services.user_service import UserService,clear_token_cache
    from app.db import close_database_provider
    service=UserService();uid='U20270000081'
    await service.create_users([uid],daily_quota=10,created_by='U20270000002')
    await service.set_enabled(uid,True)
    token,_=await service.issue_token(uid)
    clear_token_cache()
    before=await service.resolve_token(token)
    assert before is not None and before.subject==uid
    await service.set_enabled(uid,False)
    clear_token_cache()
    assert await service.resolve_token(token) is None
    await service.set_enabled(uid,True)
    clear_token_cache()
    assert await service.resolve_token(token) is None
    replacement,_=await service.issue_token(uid)
    clear_token_cache()
    assert (await service.resolve_token(replacement)).subject==uid
    await service.revoke_token(replacement)
    clear_token_cache()
    report={'case_id':'auth-account-valid-to-disabled','status':'通过','baseline_id':BASELINE,
        'database':identity['database'],'assertions':{
            'fresh_token_valid_before_disable':True,'same_token_invalid_after_disable':True,
            'old_token_not_revived_after_enable':True,'new_token_valid_after_enable':True},
        'scope':'真实冻结UserService与隔离PG；不记录明文token/密码/hash；独立补验不改变已冻结HTTP trace'}
    path=ROOT/'docs/java-migration/evidence/J0-02/auto/account-disable-followup.json'
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    close_database_provider()
    print(json.dumps({'case_id':report['case_id'],'status':'pass','assertions':4}))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--database',choices=['p0_candidate_oracle'],required=True)
    asyncio.run(execute(parser.parse_args().database))

if __name__=='__main__':main()

"""P5原始合成输入种子；不读取Java输出生成黄金，不重置既有库。"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
from acceptance.contract.isolation import PgIsolation, OwnedDatabase

CLOCK='2026-10-24T04:00:00+00:00'
IDS={'leader':'U20270000001','teacher_a':'U20270000002','teacher_b':'U20270000003','student_a':'U20270000011','student_b':'U20270000021','free':'U20270000031','delete':'U20270000041','disabled':'U20270000051','operator':'U20270000071'}
RELEASE='p0-synthetic-release-1'
HISTORY_SESSION='sess_aaaaaaaaaaa1';HISTORY_MESSAGE='msg_aaaaaaaaaaa2';FAILED_SESSION='sess_aaaaaaaaaaa3';FAILED_MESSAGE='a'*31+'4';FAILED_TASK='a'*31+'5'

def seed(isolation, db, control_url, profile='p0'):
    isolation.verify_owned(db)
    import bcrypt
    state=json.load(urlopen(control_url+'/state'))
    with isolation._connect(db.name) as c:
        if c.execute('SELECT count(*) FROM users').fetchone()[0]:raise RuntimeError('种子要求空库；拒绝重置已有数据')
        for who,uid in IDS.items():
            if who=='operator' and profile=='p0':continue
            role='leader' if who=='leader' else 'admin' if who.startswith('teacher_') else 'operator' if who=='operator' else 'viewer'
            creator=IDS['leader'] if who.startswith('teacher_') else IDS['teacher_a'] if who in ('student_a','delete','disabled') else IDS['teacher_b'] if who=='student_b' else None
            c.execute('INSERT INTO users(id,password_hash,roles,daily_quota,enabled,must_change_password,created_by,created_at) VALUES (%s,%s,%s,10000,true,true,%s,%s)',(uid,bcrypt.hashpw(uid.encode(),bcrypt.gensalt(4)).decode(),json.dumps([role]),creator,CLOCK))
        c.execute('INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,3)',(RELEASE,state['configuration'],CLOCK))
        c.execute("INSERT INTO rag_index_state(name,release_id) VALUES ('active',%s) ON CONFLICT(name) DO UPDATE SET release_id=excluded.release_id",(RELEASE,))
        for i in range(3):
            node='li_p0_node_'+str(i);vector=[float(j==i) for j in range(1024)]
            c.execute('INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)',(node,'DNS 解析报文中的域名',json.dumps({'source_path':f'synthetic/dns{i//2}.md','title':'DNS','section':'parse'},ensure_ascii=False),str(vector)))
            c.execute('INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)',(RELEASE,node))
        c.execute('INSERT INTO knowledge_documents(id,source_path,source_type,title,content_hash,indexed_at) VALUES (%s,%s,%s,%s,%s,%s)',('p0-doc','synthetic/history.md','markdown','历史DNS','0'*64,CLOCK))
        c.execute('INSERT INTO knowledge_chunks(id,document_id,source_path,section,content,metadata_json) VALUES (%s,%s,%s,%s,%s,%s::jsonb)',('p0-history','p0-doc','synthetic/history.md','历史','历史固定来源','{}'))
        c.execute('INSERT INTO assistant_sessions(id,user_id,title,created_at,updated_at) VALUES (%s,%s,%s,%s,%s)',(HISTORY_SESSION,IDS['student_a'],'合成历史',CLOCK,CLOCK))
        c.execute('INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) VALUES (%s,%s,%s,%s,%s::jsonb,%s)',(HISTORY_MESSAGE,HISTORY_SESSION,'assistant','历史固定回答','{"confidence":"medium"}',CLOCK))
        c.execute('INSERT INTO assistant_citations(id,message_id,chunk_id,score,rank) VALUES (%s,%s,%s,1,1)',('cit_aaaaaaaaaaa6',HISTORY_MESSAGE,'p0-history'))
        c.execute('INSERT INTO assistant_sessions(id,user_id,title,created_at,updated_at) VALUES (%s,%s,%s,%s,%s)',(FAILED_SESSION,IDS['student_a'],'合成未解答',CLOCK,CLOCK))
        c.execute('INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) VALUES (%s,%s,%s,%s,%s::jsonb,%s)',(FAILED_MESSAGE,FAILED_SESSION,'assistant','资料不足',json.dumps({'answer_status':'unanswered','answer_reason':'no_evidence','task_id':FAILED_TASK}),CLOCK))
        c.execute('INSERT INTO rag_requests(id,request_id,initial_request,purpose,user_id,session_id,message_id,question,context_text,status,reason,release_id,evidence_json,model_version,elapsed_ms,created_at,updated_at) VALUES (%s,%s,1,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,%s,%s)',(FAILED_TASK,'p0-seed-failed','chat',IDS['student_a'],FAILED_SESSION,FAILED_MESSAGE,'合成未解答问题','','unanswered','no_evidence',RELEASE,'[]','synthetic',CLOCK,CLOCK))
        c.execute('INSERT INTO rag_issues(id,request_id,status,feedback,updated_at) VALUES (%s,%s,%s,%s,%s)',(FAILED_TASK,FAILED_TASK,'open','',CLOCK))
    return {'accounts':{k:{'username':v,'password_convention':'学号本身'} for k,v in IDS.items() if profile!='p0' or k!='operator'},'history_session':HISTORY_SESSION,'history_message':HISTORY_MESSAGE,'failed_task':FAILED_TASK,'failed_message':FAILED_MESSAGE}

def main():
    p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--database',required=True);p.add_argument('--control',required=True);p.add_argument('--profile',choices=['p0','ui'],default='p0');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    h=PgIsolation.from_env(a.manifest,allowed_prefixes=('p5_ui_',) if a.profile=='ui' else ('p5_contract_',));row=h.manifest['owned'][a.database];db=OwnedDatabase(**{k:row[k] for k in ('name','url','comment','round_id')})
    result=seed(h,db,a.control,a.profile);a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()

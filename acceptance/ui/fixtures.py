"""在显式owned UI库追加合成release；历史节点/会话不覆盖，供来源面板验收。"""
import json
from urllib.request import urlopen

def append_six_release(isolation, database, control):
    isolation.verify_owned(database)
    state = json.load(urlopen(control+'/state'))
    release = 'p5-ui-six-release'
    with isolation._connect(database.name) as connection:
        with connection.transaction():
            connection.execute('INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,6)',
                               (release,state['configuration'],'2026-10-24T04:00:00+00:00'))
            for i in range(6):
                node = 'li_p5_ui_six_'+str(i)
                metadata = {'source_path':'synthetic/ui-sources/dns'+str(i//2)+'.md',
                            'title':'UI六引用', 'section':'片段'+str(i)}
                vector = [float(j == i) for j in range(1024)]
                connection.execute('INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)',
                                   (node,'DNS 解析报文中的域名，合成片段'+str(i),json.dumps(metadata,ensure_ascii=False),str(vector)))
                connection.execute('INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)',(release,node))
            connection.execute("UPDATE rag_index_state SET release_id=%s WHERE name='active'",(release,))
    return release

def append_untrusted_markdown(isolation, database):
    """仅检验既存历史的浏览器渲染；不声称通过模型答案校验或新增产品业务。"""
    isolation.verify_owned(database)
    session, message = 'sess_555555555555', 'msg_555555555556'
    content = ('# 合成 Markdown 验收\n\n**粗体保留** [安全链接](https://example.invalid/)\n\n'
               '[恶意链接](javascript:window.__p5Xss=1)\n'
               '<img src=x onerror="window.__p5Xss=1">\n'
               '<script>window.__p5Xss=1</script>\n\n固定证据 [1]。')
    with isolation._connect(database.name) as connection:
        with connection.transaction():
            connection.execute('INSERT INTO assistant_sessions(id,user_id,title,created_at,updated_at) VALUES (%s,%s,%s,%s,%s)',
                               (session,'U20270000011','合成 Markdown 验收','2026-10-24T04:00:00+00:00','2026-10-24T04:00:00+00:00'))
            connection.execute('INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) VALUES (%s,%s,%s,%s,%s::jsonb,%s)',
                               (message,session,'assistant',content,json.dumps({'confidence':'high','answer_status':'answered','answer_reason':'supported'}),'2026-10-24T04:00:00+00:00'))
            connection.execute('INSERT INTO assistant_citations(id,message_id,chunk_id,score,rank) VALUES (%s,%s,%s,1,1)',
                               ('cit_555555555557',message,'p0-history'))
    return session

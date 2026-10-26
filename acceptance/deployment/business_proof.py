"""保留public表完整行的有序多重集SHA-256证明；从不导出原始行。"""
from __future__ import annotations
import json
import re
from guard import GuardError

EXCLUDED='rag_task_inputs'
VERSION='public-full-row-multiset-sha256-v1'


def proof_sql(tables):
    """一条只读快照SELECT：每行canonical jsonb→SHA256，按摘要C序排序聚合再SHA256。"""
    tables=sorted(tables)
    if not tables or len(set(tables))!=len(tables) or EXCLUDED not in tables:
        raise GuardError('缺失/重复public表或临时输入表结构')
    if any(not re.fullmatch('[a-zA-Z_][a-zA-Z0-9_]*',name) for name in tables):
        raise GuardError('不支持的public表标识')
    rows=[]
    for table in tables:
        data="NULL::text" if table==EXCLUDED else (
            "(SELECT encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h COLLATE \"C\"),''),'UTF8')),'hex') "
            'FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,\'UTF8\')),\'hex\') h FROM public."'+table+'" t) hashed)')
        schema=("(SELECT encode(sha256(convert_to(coalesce(jsonb_agg(jsonb_build_array(a.position,a.attname,"
                "format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,pg_get_expr(d.adbin,d.adrelid)) "
                "ORDER BY a.position)::text,'[]'),'UTF8')),'hex') FROM (SELECT attrelid,attnum,attname,atttypid,atttypmod,attnotnull,attidentity,attgenerated,attisdropped,row_number() OVER (ORDER BY attnum) position FROM pg_attribute WHERE attrelid='public.\""+table+"\"'::regclass AND attnum>0 AND NOT attisdropped) a "
                "LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
                "WHERE a.attrelid='public.\""+table+"\"'::regclass AND a.attnum>0 AND NOT a.attisdropped)")
        rows.append("SELECT '"+table+"' AS name,jsonb_build_object('rows',(SELECT count(*) FROM public.\""+table+
                    "\"),'row_sha256',"+data+",'column_schema_sha256',"+schema+",'data_excluded',"+
                    ('true' if table==EXCLUDED else 'false')+") AS proof")
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL TIME ZONE 'UTC'; "
            "SET LOCAL extra_float_digits=3; SELECT jsonb_build_object('version','"+VERSION+"','inventory',"
            "(SELECT jsonb_agg(tablename ORDER BY tablename COLLATE \"C\") FROM pg_tables WHERE schemaname='public'),"
            "'tables',(SELECT jsonb_object_agg(name,proof) FROM ("+' UNION ALL '.join(rows)+") p)); COMMIT;")


def parse_proof(raw,tables):
    lines=[line for line in raw.splitlines() if line.startswith('{')]
    if len(lines)!=1:raise GuardError('行摘要证明输出不是唯一JSON对象')
    proof=json.loads(lines[0])
    expected=sorted(tables)
    if proof.get('version')!=VERSION or proof.get('inventory')!=expected or sorted(proof.get('tables',{}))!=expected:
        raise GuardError('快照public表清单变化/证明域不符')
    for name,item in proof['tables'].items():
        if type(item.get('rows')) is not int or item['rows']<0 or item.get('data_excluded')!=(name==EXCLUDED):
            raise GuardError('表摘要计数/排除域非法')
        for key in ('column_schema_sha256',) if name==EXCLUDED else ('column_schema_sha256','row_sha256'):
            if not re.fullmatch('[0-9a-f]{64}',item.get(key,'')):raise GuardError('表摘要格式非法')
        if name==EXCLUDED and item.get('row_sha256') is not None:raise GuardError('临时源码不应生成数据摘要')
    return proof


def assert_retained_equal(before,after):
    """数据/列结构逐项相等；临时输入仅保列结构且目标数据零行。错误只含表名。"""
    before=parse_proof(json.dumps(before),before.get('inventory',[]))
    after=parse_proof(json.dumps(after),before['inventory'])
    for table,left in before['tables'].items():
        right=after['tables'][table]
        if left['column_schema_sha256']!=right['column_schema_sha256']:
            raise GuardError('恢复列结构变化：'+table)
        if table==EXCLUDED:
            if right['rows']!=0:raise GuardError('恢复临时输入数据泄漏')
        elif left['rows']!=right['rows'] or left['row_sha256']!=right['row_sha256']:
            raise GuardError('恢复保留完整行变化：'+table)
    return {'domain':VERSION,'retained_public_tables':len(before['tables'])-1,
            'retained_full_row_multisets_equal':True,'temporary_input_schema_equal':True,
            'temporary_input_rows_restored':0,'raw_rows_exported':False,
            'sequence_values_constraints_indexes_not_proven_by_this_digest':True}

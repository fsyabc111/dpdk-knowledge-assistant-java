#!/usr/bin/env python3
"""仅显式loopback /metrics的只读HTTP采样；不启动服务、不连接/修改数据库种子。"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url',required=True);parser.add_argument('--source-revision',required=True)
parser.add_argument('--case-id',required=True);parser.add_argument('--output-prefix',type=Path,required=True);args=parser.parse_args()
parsed=urllib.parse.urlsplit(args.url)
if parsed.scheme!='http' or parsed.hostname not in ('127.0.0.1','localhost','::1') or parsed.port is None or parsed.path!='/metrics' or parsed.query or parsed.fragment or parsed.username or parsed.password:
    raise SystemExit('only explicit anonymous loopback HTTP /metrics with port is allowed')
if not args.output_prefix.is_absolute():raise SystemExit('output prefix must be absolute')
paths=[Path(str(args.output_prefix)+suffix) for suffix in ('.prom','.json')]
if any(path.exists() for path in paths):raise SystemExit('refuse to overwrite raw evidence')
try:response=urllib.request.urlopen(args.url,timeout=15)
except urllib.error.HTTPError as error:response=error
with response:body=response.read();status=response.status;media=response.headers.get('Content-Type','')
metadata={'captured_at':datetime.now(timezone.utc).isoformat(),'source_revision':args.source_revision,'source_attribution':'provided_by_root_not_discovered_from_response',
    'case_id':args.case_id,'url':args.url,'status':status,'media_type':media,'body_sha256':hashlib.sha256(body).hexdigest(),'body_bytes':len(body),
    'capture_mutations':'HTTP metrics bookkeeping only; no database seed mutation or service start'}
paths[0].parent.mkdir(parents=True,exist_ok=True)
for path,content in ((paths[0],body),(paths[1],(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n').encode())):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as output:output.write(content)
print(json.dumps(metadata,ensure_ascii=False))

#!/usr/bin/env python3
"""专属Docker PG备份包装；不依赖Python主后端。"""
import argparse
import json
import os
from pathlib import Path
from guard import GuardError,env_values,docker_configuration,deployment_manifest
from docker_pg import DockerPg,BASE
from override import validate

parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path)
parser.add_argument('--apply',action='store_true');parser.add_argument('destination',type=Path)
args=parser.parse_args();repo=args.repo.resolve();root=repo/'.migration-artifacts/j5-03'
if not args.destination.is_absolute() or args.destination.exists() or not args.destination.resolve().is_relative_to(root):raise GuardError('只允许本轮尚不存在的绝对备份路径')
env_values(repo,os.environ.get('DPDK_JAVA_ENV_FILE',''));docker_configuration();deployment_manifest(repo)
name=BASE
if os.environ.get('DPDK_JAVA_COMPOSE_OVERRIDE'):
    overlay=validate(repo,os.environ['DPDK_JAVA_COMPOSE_OVERRIDE'])
    name=overlay['services']['backend-java'].get('environment',{}).get('DPDK_JAVA_DB_NAME',BASE)
if not args.apply:
    print(json.dumps({'dry_run':True,'database':name,'exclude_table_data':'public.rag_task_inputs','schema_retained':True,'database_connected':False}));raise SystemExit(0)
print(json.dumps(DockerPg(repo).backup(name,args.destination)))

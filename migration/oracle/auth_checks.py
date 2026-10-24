"""三认证模式、账户身份和规范化的确定性向量；不冒充全端点三模式回归。"""
from __future__ import annotations
import hashlib
import json
from unittest.mock import patch
from .runtime import IDS


async def run(c):
    from starlette.requests import Request
    from fastapi import HTTPException
    from app.config import get_settings
    from app.core import auth
    from app.services.user_service import UserService,clear_token_cache
    from app.services.rag_issue_service import input_hash
    from app.db import get_conn
    results=[]
    def request(token=None):return Request({'type':'http','headers':[(b'authorization',('Bearer '+token).encode())] if token else [],'client':('127.0.0.1',12345)})
    def record(name,assertion):results.append({'case_id':name,'status':'通过','assertion':assertion})
    settings=get_settings();static='p0-synthetic-static-token-value-0001'
    spec=json.dumps({static:{'subject':'p0-token-viewer','roles':['viewer'],'daily_quota':5}})
    with patch.object(auth,'get_settings',lambda:settings.model_copy(update={'auth_mode':'local'})):
        principal=await auth.require_auth(request())
        assert principal.subject=='local' and principal.roles==['admin'] and principal.daily_quota==0
    record('auth-local','真实require_auth local免Bearer产生唯一local/admin主体')
    with patch.object(auth,'get_settings',lambda:settings.model_copy(update={'auth_mode':'token','api_tokens_json':spec})):
        assert (await auth.require_auth(request(static))).subject=='p0-token-viewer'
        for token in [None,'synthetic-invalid']:
            try:await auth.require_auth(request(token))
            except HTTPException as exc:assert exc.status_code==401 and exc.headers['WWW-Authenticate']=='Bearer'
            else:raise AssertionError('token无效未拒绝')
    record('auth-token','有效静态主体/角色/配额保持；缺失和无效token均401+WWW-Authenticate')
    token=c.tokens['student_a'];user=UserService()
    assert (await auth.require_auth(request(token))).subject==IDS['student_a']
    await user.set_enabled(IDS['disabled'],False)
    clear_token_cache()
    # 已logout的旧token应拒绝；重新enable也不能复活撤销token。
    assert await user.resolve_token(c.tokens['disabled']) is None
    await user.set_enabled(IDS['disabled'],True)
    assert await user.resolve_token(c.tokens['disabled']) is None
    record('auth-account-disabled-revoked','已logout令牌在停用/重新启用后仍无效；有效token转停用的独立向量尚未覆盖')
    with get_conn() as conn:
        row=conn.execute('SELECT token_hash FROM api_tokens WHERE user_id=%s ORDER BY issued_at LIMIT 1',(IDS['student_a'],)).fetchone()
        assert row['token_hash']==hashlib.sha256(token.encode()).hexdigest() and row['token_hash']!=token
        hashed=conn.execute('SELECT password_hash FROM users WHERE id=%s',(IDS['student_a'],)).fetchone()['password_hash']
    import bcrypt
    assert hashed.startswith('$2b$04$') and bcrypt.checkpw(IDS['student_a'].encode(),hashed.encode())
    record('auth-storage','真实token仅SHA256摘要入库；合成bcrypt $2b$/cost4格式可verify（正式cost12在性能组）')
    assert input_hash('  cafe\u0301\n')==input_hash('café')
    assert input_hash('a b')!=input_hash('a  b')
    assert input_hash('AB')!=input_hash('ab')
    from app.schemas.chat import UploadedCode
    one=UploadedCode(filename=' x.c ',suffix='.c',source='synthetic',content_hash='a'*64)
    two=UploadedCode(filename='x.c',suffix='.c',source='synthetic',content_hash='a'*64)
    assert input_hash('问题',one)==input_hash('问题',two)
    two.content_hash='b'*64
    assert input_hash('问题',one)!=input_hash('问题',two)
    record('identity-nfc-strip','真实input_hash仅NFC/首尾Python strip；内部空白、大小写、附件原始字节摘要保持区别')
    return results

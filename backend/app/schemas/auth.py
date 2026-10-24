from typing import List, Optional

from pydantic import BaseModel, Field


class LoginRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=1, max_length=256)


class LoginResponse(BaseModel):
    token: str
    subject: str
    roles: List[str]
    expires_at: str
    must_change_password: bool


class ChangePasswordRequest(BaseModel):
    old_password: str = Field(..., min_length=1, max_length=256)
    new_password: str = Field(..., min_length=1, max_length=256)


class SetPasswordRequest(BaseModel):
    new_password: str = Field(..., min_length=1, max_length=256)


class PasswordResetRequestCreate(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)


class PasswordResetConfirm(BaseModel):
    token: str = Field(..., min_length=1, max_length=256)
    new_password: str = Field(..., min_length=1, max_length=256)


class PasswordResetRequestInfo(BaseModel):
    id: str
    user_id: str
    created_at: str


class PasswordResetRequestListResponse(BaseModel):
    requests: List[PasswordResetRequestInfo]


class PasswordResetApproveResponse(BaseModel):
    reset_token: str
    expires_at: str


class CreateUsersRequest(BaseModel):
    ids: List[str] = Field(..., min_length=1, max_length=500)
    roles: Optional[List[str]] = None
    daily_quota: Optional[int] = Field(default=None, ge=0)


class CreateUsersResponse(BaseModel):
    created: List[str]
    skipped: List[str]


class UserStatusRequest(BaseModel):
    enabled: bool


class UpdateUserRequest(BaseModel):
    roles: Optional[List[str]] = None
    daily_quota: Optional[int] = Field(default=None, ge=0)
    created_by: Optional[str] = None


class UserInfo(BaseModel):
    id: str
    roles: List[str]
    daily_quota: Optional[int] = None
    enabled: bool
    must_change_password: bool
    created_at: str
    created_by: Optional[str] = None


class UserListResponse(BaseModel):
    users: List[UserInfo]


class DeleteUsersRequest(BaseModel):
    """批量删除账号：每次操作都必须附操作者自己的密码。"""
    ids: List[str] = Field(..., min_length=1, max_length=500)
    password: str = Field(..., min_length=1, max_length=256)


class DeleteUsersResponse(BaseModel):
    deleted: List[str]
    skipped: List[str]


class AssignStudentsRequest(BaseModel):
    """组长把未分配学生批量指派给某位老师。"""
    ids: List[str] = Field(..., min_length=1, max_length=500)
    created_by: str = Field(..., min_length=1, max_length=64)


class AssignStudentsResponse(BaseModel):
    assigned: List[str]
    skipped: List[str]


class AuditLogInfo(BaseModel):
    id: str
    actor: str
    action: str
    targets: List[str]
    created_at: str


class AuditLogListResponse(BaseModel):
    entries: List[AuditLogInfo]

from fastapi import APIRouter, Depends

from ..core.auth import Principal, require_auth
from ..schemas.chat import TemplateRequest, TemplateResponse
from ..services.template_service import TemplateService

router = APIRouter(prefix="/api", tags=["templates"])


@router.post("/plugin-template", response_model=TemplateResponse)
def plugin_template(req: TemplateRequest, principal: Principal = Depends(require_auth)) -> TemplateResponse:
    return TemplateService().generate(req)


from fastapi import APIRouter

from app.core.responses import success

router = APIRouter(tags=["tools"])


@router.get("/tools")
async def get_optional_tools():
    return success(
        [
            {
                "name": "terminate",
                "description": "Finish the current agent run.",
                "type": "FIXED",
            },
            {
                "name": "directAnswer",
                "description": "Return a direct answer without external tools.",
                "type": "FIXED",
            },
        ]
    )

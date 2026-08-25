from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.api.deps import get_sse_publisher

router = APIRouter(tags=["sse"])


@router.get("/connect/{chat_session_id}")
async def connect(chat_session_id: str, sse_publisher=Depends(get_sse_publisher)):
    return StreamingResponse(
        sse_publisher.stream(chat_session_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
    )

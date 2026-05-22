from fastapi import APIRouter, Depends

from app.api.deps import get_store
from app.core.responses import success
from app.schemas.chat_session import CreateChatSessionRequest, UpdateChatSessionRequest

router = APIRouter(tags=["chat-sessions"])


@router.get("/chat-sessions")
async def get_chat_sessions(store=Depends(get_store)):
    sessions = await store.list_chat_sessions()
    return success({"chatSessions": [session.to_public_dict() for session in sessions]})


@router.get("/chat-sessions/{chat_session_id}")
async def get_chat_session(chat_session_id: str, store=Depends(get_store)):
    session = await store.get_chat_session(chat_session_id)
    return success({"chatSession": session.to_public_dict() if session else None})


@router.get("/chat-sessions/agent/{agent_id}")
async def get_chat_sessions_by_agent(agent_id: str, store=Depends(get_store)):
    sessions = await store.list_chat_sessions(agent_id=agent_id)
    return success({"chatSessions": [session.to_public_dict() for session in sessions]})


@router.post("/chat-sessions")
async def create_chat_session(
    request: CreateChatSessionRequest, store=Depends(get_store)
):
    session = await store.create_chat_session(request)
    return success({"chatSessionId": session.id})


@router.delete("/chat-sessions/{chat_session_id}")
async def delete_chat_session(chat_session_id: str, store=Depends(get_store)):
    await store.delete_chat_session(chat_session_id)
    return success()


@router.patch("/chat-sessions/{chat_session_id}")
async def update_chat_session(
    chat_session_id: str, request: UpdateChatSessionRequest, store=Depends(get_store)
):
    await store.update_chat_session(chat_session_id, request)
    return success()

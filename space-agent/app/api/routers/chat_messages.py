from fastapi import APIRouter, Depends

from app.api.deps import get_agent_runtime, get_store
from app.core.responses import success
from app.schemas.chat_message import CreateChatMessageRequest, UpdateChatMessageRequest

router = APIRouter(tags=["chat-messages"])


@router.get("/chat-messages/session/{session_id}")
async def get_chat_messages_by_session(session_id: str, store=Depends(get_store)):
    messages = await store.list_chat_messages(session_id)
    return success({"chatMessages": [message.to_public_dict() for message in messages]})


@router.post("/chat-messages")
async def create_chat_message(
    request: CreateChatMessageRequest,
    store=Depends(get_store),
    agent_runtime=Depends(get_agent_runtime),
):
    message = await store.create_chat_message(request)
    if request.role == "user":
        await agent_runtime.run(
            agent_id=request.agent_id,
            chat_session_id=request.session_id,
            user_message=message,
        )
    return success({"chatMessageId": message.id})


@router.delete("/chat-messages/{chat_message_id}")
async def delete_chat_message(chat_message_id: str, store=Depends(get_store)):
    await store.delete_chat_message(chat_message_id)
    return success()


@router.patch("/chat-messages/{chat_message_id}")
async def update_chat_message(
    chat_message_id: str, request: UpdateChatMessageRequest, store=Depends(get_store)
):
    await store.update_chat_message(chat_message_id, request)
    return success()

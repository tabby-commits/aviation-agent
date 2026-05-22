from fastapi import APIRouter

from app.api.routers import (
    agents,
    chat_messages,
    chat_sessions,
    documents,
    knowledge_bases,
    sse,
    tools,
)

api_router = APIRouter()
api_router.include_router(agents.router)
api_router.include_router(chat_sessions.router)
api_router.include_router(chat_messages.router)
api_router.include_router(knowledge_bases.router)
api_router.include_router(documents.router)
api_router.include_router(tools.router)

sse_router = APIRouter()
sse_router.include_router(sse.router)

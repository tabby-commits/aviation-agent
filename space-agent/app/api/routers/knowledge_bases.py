from fastapi import APIRouter, Depends

from app.api.deps import get_store
from app.core.responses import success
from app.schemas.knowledge_base import (
    CreateKnowledgeBaseRequest,
    UpdateKnowledgeBaseRequest,
)

router = APIRouter(tags=["knowledge-bases"])


@router.get("/knowledge-bases")
async def get_knowledge_bases(store=Depends(get_store)):
    knowledge_bases = await store.list_knowledge_bases()
    return success(
        {"knowledgeBases": [kb.to_public_dict() for kb in knowledge_bases]}
    )


@router.post("/knowledge-bases")
async def create_knowledge_base(
    request: CreateKnowledgeBaseRequest, store=Depends(get_store)
):
    knowledge_base = await store.create_knowledge_base(request)
    return success({"knowledgeBaseId": knowledge_base.id})


@router.delete("/knowledge-bases/{knowledge_base_id}")
async def delete_knowledge_base(knowledge_base_id: str, store=Depends(get_store)):
    await store.delete_knowledge_base(knowledge_base_id)
    return success()


@router.patch("/knowledge-bases/{knowledge_base_id}")
async def update_knowledge_base(
    knowledge_base_id: str,
    request: UpdateKnowledgeBaseRequest,
    store=Depends(get_store),
):
    await store.update_knowledge_base(knowledge_base_id, request)
    return success()

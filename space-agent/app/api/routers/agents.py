from fastapi import APIRouter, Depends

from app.api.deps import get_store
from app.core.responses import success
from app.schemas.agent import CreateAgentRequest, UpdateAgentRequest

router = APIRouter(tags=["agents"])


@router.get("/agents")
async def get_agents(store=Depends(get_store)):
    agents = await store.list_agents()
    return success({"agents": [agent.to_public_dict() for agent in agents]})


@router.post("/agents")
async def create_agent(request: CreateAgentRequest, store=Depends(get_store)):
    agent = await store.create_agent(request)
    return success({"agentId": agent.id})


@router.delete("/agents/{agent_id}")
async def delete_agent(agent_id: str, store=Depends(get_store)):
    await store.delete_agent(agent_id)
    return success()


@router.patch("/agents/{agent_id}")
async def update_agent(
    agent_id: str, request: UpdateAgentRequest, store=Depends(get_store)
):
    await store.update_agent(agent_id, request)
    return success()

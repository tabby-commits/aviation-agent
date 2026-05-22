from typing import Protocol, runtime_checkable

from app.schemas.chat_message import ChatMessageRecord


@runtime_checkable
class AgentRuntimePort(Protocol):
    async def run(
        self,
        agent_id: str,
        chat_session_id: str,
        user_message: ChatMessageRecord,
    ) -> None:
        """Run one asynchronous agent turn."""

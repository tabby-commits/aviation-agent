from app.schemas.chat_message import ChatMessageRecord


class StubAgentRuntime:
    def __init__(self, store, sse_publisher) -> None:
        self.store = store
        self.sse_publisher = sse_publisher

    async def run(
        self,
        agent_id: str,
        chat_session_id: str,
        user_message: ChatMessageRecord,
    ) -> None:
        assistant = await self.store.create_assistant_message(
            chat_session_id,
            (
                "[stub-agent] FastAPI skeleton accepted the user message. "
                "Full model/tool/RAG logic will be implemented in later modules."
            ),
            metadata={"agentId": agent_id, "sourceMessageId": user_message.id},
        )
        await self.sse_publisher.send(
            chat_session_id,
            {
                "type": "AI_GENERATED_CONTENT",
                "payload": {"message": assistant.to_public_dict()},
                "metadata": {"chatMessageId": assistant.id},
            },
        )
        await self.sse_publisher.send(
            chat_session_id,
            {"type": "AI_DONE", "payload": {"done": True}, "metadata": None},
        )

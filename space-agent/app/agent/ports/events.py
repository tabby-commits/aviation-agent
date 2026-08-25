from typing import AsyncIterator, Protocol, runtime_checkable


@runtime_checkable
class EventBusPort(Protocol):
    async def publish(self, topic: str, payload: dict[str, object]) -> None:
        """Publish an internal application event."""


@runtime_checkable
class SsePublisherPort(Protocol):
    async def send(self, chat_session_id: str, message: dict[str, object]) -> None:
        """Send an SSE message to a chat session."""

    def stream(self, chat_session_id: str) -> AsyncIterator[str]:
        """Stream encoded SSE frames for a chat session."""

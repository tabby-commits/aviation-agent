from typing import Protocol, runtime_checkable


@runtime_checkable
class LLMClientPort(Protocol):
    async def complete(self, messages: list[dict[str, object]]) -> str:
        """Return a model completion for an already prepared message list."""

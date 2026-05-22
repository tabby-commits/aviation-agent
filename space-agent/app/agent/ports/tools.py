from typing import Protocol, runtime_checkable


@runtime_checkable
class ToolRegistryPort(Protocol):
    async def list_tools(self) -> list[dict[str, object]]:
        """Return tools available to an agent runtime."""

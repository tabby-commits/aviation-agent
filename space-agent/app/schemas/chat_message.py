from typing import Any, Literal

from pydantic import Field

from app.schemas.base import ApiModel, Timestamped

RoleType = Literal["user", "assistant", "system", "tool"]


class CreateChatMessageRequest(ApiModel):
    agent_id: str = Field(alias="agentId")
    session_id: str = Field(alias="sessionId")
    role: RoleType
    content: str
    metadata: dict[str, Any] | None = None


class UpdateChatMessageRequest(ApiModel):
    content: str | None = None
    metadata: dict[str, Any] | None = None


class ChatMessageRecord(Timestamped):
    id: str
    session_id: str = Field(alias="sessionId")
    role: RoleType
    content: str
    metadata: dict[str, Any] | None = None

    def to_public_dict(self) -> dict[str, Any]:
        return self.public_dict()

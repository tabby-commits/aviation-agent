from pydantic import Field

from app.schemas.base import ApiModel, Timestamped


class CreateChatSessionRequest(ApiModel):
    agent_id: str = Field(alias="agentId")
    title: str | None = None


class UpdateChatSessionRequest(ApiModel):
    title: str | None = None


class ChatSessionRecord(Timestamped):
    id: str
    agent_id: str = Field(alias="agentId")
    title: str | None = None

    def to_public_dict(self) -> dict[str, object]:
        return self.public_dict()

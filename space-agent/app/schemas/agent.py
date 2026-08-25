from typing import Any

from pydantic import Field

from app.schemas.base import ApiModel, Timestamped


class ChatOptions(ApiModel):
    temperature: float = 0.7
    top_p: float = Field(default=1.0, alias="topP")
    message_length: int = Field(default=10, alias="messageLength")


class CreateAgentRequest(ApiModel):
    name: str
    description: str | None = None
    system_prompt: str | None = Field(default=None, alias="systemPrompt")
    model: str
    allowed_tools: list[str] = Field(default_factory=list, alias="allowedTools")
    allowed_kbs: list[str] = Field(default_factory=list, alias="allowedKbs")
    chat_options: ChatOptions = Field(
        default_factory=ChatOptions, alias="chatOptions"
    )


class UpdateAgentRequest(ApiModel):
    name: str | None = None
    description: str | None = None
    system_prompt: str | None = Field(default=None, alias="systemPrompt")
    model: str | None = None
    allowed_tools: list[str] | None = Field(default=None, alias="allowedTools")
    allowed_kbs: list[str] | None = Field(default=None, alias="allowedKbs")
    chat_options: ChatOptions | None = Field(default=None, alias="chatOptions")


class AgentRecord(Timestamped):
    id: str
    name: str
    description: str | None = None
    system_prompt: str | None = Field(default=None, alias="systemPrompt")
    model: str
    allowed_tools: list[str] = Field(default_factory=list, alias="allowedTools")
    allowed_kbs: list[str] = Field(default_factory=list, alias="allowedKbs")
    chat_options: dict[str, Any] = Field(default_factory=dict, alias="chatOptions")

    def to_public_dict(self) -> dict[str, Any]:
        return self.public_dict()

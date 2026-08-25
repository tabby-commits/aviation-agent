from typing import Any

from pydantic import Field

from app.schemas.base import ApiModel, Timestamped


class CreateDocumentRequest(ApiModel):
    kb_id: str = Field(alias="kbId")
    filename: str
    filetype: str
    size: int = 0
    metadata: dict[str, Any] | None = None


class UpdateDocumentRequest(ApiModel):
    kb_id: str | None = Field(default=None, alias="kbId")
    filename: str | None = None
    filetype: str | None = None
    size: int | None = None
    metadata: dict[str, Any] | None = None


class DocumentRecord(Timestamped):
    id: str
    kb_id: str = Field(alias="kbId")
    filename: str
    filetype: str
    size: int
    metadata: dict[str, Any] | None = None

    def to_public_dict(self) -> dict[str, Any]:
        data = self.public_dict()
        data.pop("metadata", None)
        return data

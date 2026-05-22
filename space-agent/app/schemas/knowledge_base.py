from app.schemas.base import ApiModel, Timestamped


class CreateKnowledgeBaseRequest(ApiModel):
    name: str
    description: str | None = None


class UpdateKnowledgeBaseRequest(ApiModel):
    name: str | None = None
    description: str | None = None


class KnowledgeBaseRecord(Timestamped):
    id: str
    name: str
    description: str | None = None

    def to_public_dict(self) -> dict[str, object]:
        return self.public_dict()

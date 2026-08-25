import pytest

from app.models.tables import (
    Agent,
    ChatMessage,
    ChatSession,
    ChunkBgeM3,
    Document,
    KnowledgeBase,
)


def test_sqlalchemy_models_map_existing_java_tables():
    assert Agent.__tablename__ == "agent"
    assert ChatSession.__tablename__ == "chat_session"
    assert ChatMessage.__tablename__ == "chat_message"
    assert KnowledgeBase.__tablename__ == "knowledge_base"
    assert Document.__tablename__ == "document"
    assert ChunkBgeM3.__tablename__ == "chunk_bge_m3"

    assert "system_prompt" in Agent.__table__.columns
    assert "allowed_tools" in Agent.__table__.columns
    assert "allowed_kbs" in Agent.__table__.columns
    assert "chat_options" in Agent.__table__.columns
    assert "metadata" in ChatMessage.__table__.columns
    assert "embedding" in ChunkBgeM3.__table__.columns


@pytest.mark.integration
def test_repository_smoke_requires_database_url():
    pytest.importorskip("asyncpg")

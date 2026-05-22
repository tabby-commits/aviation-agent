from __future__ import annotations

import json
from typing import Any

from fastapi import UploadFile
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.schemas.agent import AgentRecord, CreateAgentRequest, UpdateAgentRequest
from app.schemas.chat_message import (
    ChatMessageRecord,
    CreateChatMessageRequest,
    UpdateChatMessageRequest,
)
from app.schemas.chat_session import (
    ChatSessionRecord,
    CreateChatSessionRequest,
    UpdateChatSessionRequest,
)
from app.schemas.document import (
    CreateDocumentRequest,
    DocumentRecord,
    UpdateDocumentRequest,
)
from app.schemas.knowledge_base import (
    CreateKnowledgeBaseRequest,
    KnowledgeBaseRecord,
    UpdateKnowledgeBaseRequest,
)


def _json_or_default(value: Any, default: Any) -> Any:
    if value is None:
        return default
    if isinstance(value, str):
        return json.loads(value)
    return value


class SqlAlchemyStore:
    def __init__(self, database_url: str) -> None:
        self.engine = create_async_engine(database_url, pool_pre_ping=True)
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)

    async def health(self) -> dict[str, Any]:
        try:
            async with self.engine.connect() as connection:
                await connection.execute(text("SELECT 1"))
            return {"configured": True, "connected": True, "mode": "postgresql"}
        except Exception as exc:
            return {
                "configured": True,
                "connected": False,
                "mode": "postgresql",
                "error": exc.__class__.__name__,
            }

    async def list_agents(self) -> list[AgentRecord]:
        query = text(
            """
            SELECT id::text, name, description, system_prompt, model,
                   allowed_tools::text AS allowed_tools,
                   allowed_kbs::text AS allowed_kbs,
                   chat_options::text AS chat_options,
                   created_at, updated_at
            FROM agent
            ORDER BY created_at DESC
            """
        )
        async with self.session_factory() as session:
            rows = (await session.execute(query)).mappings().all()
        return [self._agent_from_row(row) for row in rows]

    async def create_agent(self, request: CreateAgentRequest) -> AgentRecord:
        query = text(
            """
            INSERT INTO agent
            (name, description, system_prompt, model, allowed_tools,
             allowed_kbs, chat_options, created_at, updated_at)
            VALUES
            (:name, :description, :system_prompt, :model,
             CAST(:allowed_tools AS jsonb), CAST(:allowed_kbs AS jsonb),
             CAST(:chat_options AS jsonb), NOW(), NOW())
            RETURNING id::text, name, description, system_prompt, model,
                      allowed_tools::text AS allowed_tools,
                      allowed_kbs::text AS allowed_kbs,
                      chat_options::text AS chat_options,
                      created_at, updated_at
            """
        )
        params = {
            "name": request.name,
            "description": request.description,
            "system_prompt": request.system_prompt,
            "model": request.model,
            "allowed_tools": json.dumps(request.allowed_tools),
            "allowed_kbs": json.dumps(request.allowed_kbs),
            "chat_options": json.dumps(request.chat_options.public_dict()),
        }
        async with self.session_factory() as session:
            row = (await session.execute(query, params)).mappings().one()
            await session.commit()
        return self._agent_from_row(row)

    async def delete_agent(self, agent_id: str) -> None:
        await self._execute("DELETE FROM agent WHERE id = CAST(:id AS uuid)", {"id": agent_id})

    async def update_agent(self, agent_id: str, request: UpdateAgentRequest) -> None:
        updates = request.model_dump(exclude_unset=True)
        assignments: list[str] = []
        params: dict[str, Any] = {"id": agent_id}
        mapping = {
            "system_prompt": "system_prompt",
            "allowed_tools": "allowed_tools",
            "allowed_kbs": "allowed_kbs",
            "chat_options": "chat_options",
        }
        for key, value in updates.items():
            column = mapping.get(key, key)
            if key in {"allowed_tools", "allowed_kbs", "chat_options"}:
                assignments.append(f"{column} = CAST(:{key} AS jsonb)")
                params[key] = json.dumps(
                    value.public_dict() if hasattr(value, "public_dict") else value
                )
            else:
                assignments.append(f"{column} = :{key}")
                params[key] = value
        if assignments:
            await self._execute(
                f"UPDATE agent SET {', '.join(assignments)}, updated_at = NOW() "
                "WHERE id = CAST(:id AS uuid)",
                params,
            )

    async def list_chat_sessions(
        self, agent_id: str | None = None
    ) -> list[ChatSessionRecord]:
        where = "WHERE agent_id = CAST(:agent_id AS uuid)" if agent_id else ""
        query = text(
            f"""
            SELECT id::text, agent_id::text, title, created_at, updated_at
            FROM chat_session
            {where}
            ORDER BY updated_at DESC
            """
        )
        async with self.session_factory() as session:
            rows = (await session.execute(query, {"agent_id": agent_id})).mappings().all()
        return [self._chat_session_from_row(row) for row in rows]

    async def get_chat_session(self, chat_session_id: str) -> ChatSessionRecord | None:
        query = text(
            """
            SELECT id::text, agent_id::text, title, created_at, updated_at
            FROM chat_session
            WHERE id = CAST(:id AS uuid)
            """
        )
        async with self.session_factory() as session:
            row = (await session.execute(query, {"id": chat_session_id})).mappings().first()
        return self._chat_session_from_row(row) if row else None

    async def create_chat_session(
        self, request: CreateChatSessionRequest
    ) -> ChatSessionRecord:
        query = text(
            """
            INSERT INTO chat_session (agent_id, title, created_at, updated_at)
            VALUES (CAST(:agent_id AS uuid), :title, NOW(), NOW())
            RETURNING id::text, agent_id::text, title, created_at, updated_at
            """
        )
        async with self.session_factory() as session:
            row = (
                await session.execute(
                    query, {"agent_id": request.agent_id, "title": request.title}
                )
            ).mappings().one()
            await session.commit()
        return self._chat_session_from_row(row)

    async def delete_chat_session(self, chat_session_id: str) -> None:
        await self._execute(
            "DELETE FROM chat_session WHERE id = CAST(:id AS uuid)",
            {"id": chat_session_id},
        )

    async def update_chat_session(
        self, chat_session_id: str, request: UpdateChatSessionRequest
    ) -> None:
        if request.title is not None:
            await self._execute(
                "UPDATE chat_session SET title = :title, updated_at = NOW() "
                "WHERE id = CAST(:id AS uuid)",
                {"id": chat_session_id, "title": request.title},
            )

    async def list_chat_messages(self, session_id: str) -> list[ChatMessageRecord]:
        query = text(
            """
            SELECT id::text, session_id::text, role, content,
                   metadata::text AS metadata, created_at, updated_at
            FROM chat_message
            WHERE session_id = CAST(:session_id AS uuid)
            ORDER BY created_at ASC
            """
        )
        async with self.session_factory() as session:
            rows = (
                await session.execute(query, {"session_id": session_id})
            ).mappings().all()
        return [self._chat_message_from_row(row) for row in rows]

    async def create_chat_message(
        self, request: CreateChatMessageRequest
    ) -> ChatMessageRecord:
        query = text(
            """
            INSERT INTO chat_message
            (session_id, role, content, metadata, created_at, updated_at)
            VALUES
            (CAST(:session_id AS uuid), :role, :content,
             CAST(:metadata AS jsonb), NOW(), NOW())
            RETURNING id::text, session_id::text, role, content,
                      metadata::text AS metadata, created_at, updated_at
            """
        )
        params = {
            "session_id": request.session_id,
            "role": request.role,
            "content": request.content,
            "metadata": json.dumps(request.metadata or {}),
        }
        async with self.session_factory() as session:
            row = (await session.execute(query, params)).mappings().one()
            await session.commit()
        return self._chat_message_from_row(row)

    async def create_assistant_message(
        self, session_id: str, content: str, metadata: dict[str, Any] | None = None
    ) -> ChatMessageRecord:
        return await self.create_chat_message(
            CreateChatMessageRequest(
                agentId="",
                sessionId=session_id,
                role="assistant",
                content=content,
                metadata=metadata,
            )
        )

    async def delete_chat_message(self, chat_message_id: str) -> None:
        await self._execute(
            "DELETE FROM chat_message WHERE id = CAST(:id AS uuid)",
            {"id": chat_message_id},
        )

    async def update_chat_message(
        self, chat_message_id: str, request: UpdateChatMessageRequest
    ) -> None:
        updates = request.model_dump(exclude_unset=True)
        assignments = []
        params: dict[str, Any] = {"id": chat_message_id}
        if "content" in updates:
            assignments.append("content = :content")
            params["content"] = updates["content"]
        if "metadata" in updates:
            assignments.append("metadata = CAST(:metadata AS jsonb)")
            params["metadata"] = json.dumps(updates["metadata"] or {})
        if assignments:
            await self._execute(
                f"UPDATE chat_message SET {', '.join(assignments)}, updated_at = NOW() "
                "WHERE id = CAST(:id AS uuid)",
                params,
            )

    async def list_knowledge_bases(self) -> list[KnowledgeBaseRecord]:
        query = text(
            """
            SELECT id::text, name, description, created_at, updated_at
            FROM knowledge_base
            ORDER BY updated_at DESC
            """
        )
        async with self.session_factory() as session:
            rows = (await session.execute(query)).mappings().all()
        return [self._knowledge_base_from_row(row) for row in rows]

    async def create_knowledge_base(
        self, request: CreateKnowledgeBaseRequest
    ) -> KnowledgeBaseRecord:
        query = text(
            """
            INSERT INTO knowledge_base (name, description, created_at, updated_at)
            VALUES (:name, :description, NOW(), NOW())
            RETURNING id::text, name, description, created_at, updated_at
            """
        )
        async with self.session_factory() as session:
            row = (
                await session.execute(
                    query, {"name": request.name, "description": request.description}
                )
            ).mappings().one()
            await session.commit()
        return self._knowledge_base_from_row(row)

    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        await self._execute(
            "DELETE FROM knowledge_base WHERE id = CAST(:id AS uuid)",
            {"id": knowledge_base_id},
        )

    async def update_knowledge_base(
        self, knowledge_base_id: str, request: UpdateKnowledgeBaseRequest
    ) -> None:
        updates = request.model_dump(exclude_unset=True)
        assignments = []
        params: dict[str, Any] = {"id": knowledge_base_id}
        for key, value in updates.items():
            assignments.append(f"{key} = :{key}")
            params[key] = value
        if assignments:
            await self._execute(
                f"UPDATE knowledge_base SET {', '.join(assignments)}, updated_at = NOW() "
                "WHERE id = CAST(:id AS uuid)",
                params,
            )

    async def list_documents(self, kb_id: str | None = None) -> list[DocumentRecord]:
        where = "WHERE kb_id = CAST(:kb_id AS uuid)" if kb_id else ""
        query = text(
            f"""
            SELECT id::text, kb_id::text, filename, filetype, size,
                   metadata::text AS metadata, created_at, updated_at
            FROM document
            {where}
            ORDER BY updated_at DESC
            """
        )
        async with self.session_factory() as session:
            rows = (await session.execute(query, {"kb_id": kb_id})).mappings().all()
        return [self._document_from_row(row) for row in rows]

    async def create_document(self, request: CreateDocumentRequest) -> DocumentRecord:
        query = text(
            """
            INSERT INTO document
            (kb_id, filename, filetype, size, metadata, created_at, updated_at)
            VALUES
            (CAST(:kb_id AS uuid), :filename, :filetype, :size,
             CAST(:metadata AS jsonb), NOW(), NOW())
            RETURNING id::text, kb_id::text, filename, filetype, size,
                      metadata::text AS metadata, created_at, updated_at
            """
        )
        params = {
            "kb_id": request.kb_id,
            "filename": request.filename,
            "filetype": request.filetype,
            "size": request.size,
            "metadata": json.dumps(request.metadata or {}),
        }
        async with self.session_factory() as session:
            row = (await session.execute(query, params)).mappings().one()
            await session.commit()
        return self._document_from_row(row)

    async def create_uploaded_document(
        self, kb_id: str, file: UploadFile
    ) -> DocumentRecord:
        content = await file.read()
        return await self.create_document(
            CreateDocumentRequest(
                kbId=kb_id,
                filename=file.filename or "upload",
                filetype=file.content_type or "application/octet-stream",
                size=len(content),
                metadata={},
            )
        )

    async def delete_document(self, document_id: str) -> None:
        await self._execute(
            "DELETE FROM document WHERE id = CAST(:id AS uuid)", {"id": document_id}
        )

    async def update_document(
        self, document_id: str, request: UpdateDocumentRequest
    ) -> None:
        updates = request.model_dump(exclude_unset=True)
        assignments = []
        params: dict[str, Any] = {"id": document_id}
        for key, value in updates.items():
            column = "kb_id" if key == "kb_id" else key
            if key == "metadata":
                assignments.append("metadata = CAST(:metadata AS jsonb)")
                params[key] = json.dumps(value or {})
            elif key == "kb_id":
                assignments.append("kb_id = CAST(:kb_id AS uuid)")
                params[key] = value
            else:
                assignments.append(f"{column} = :{key}")
                params[key] = value
        if assignments:
            await self._execute(
                f"UPDATE document SET {', '.join(assignments)}, updated_at = NOW() "
                "WHERE id = CAST(:id AS uuid)",
                params,
            )

    async def _execute(self, sql: str, params: dict[str, Any]) -> None:
        async with self.session_factory() as session:
            await session.execute(text(sql), params)
            await session.commit()

    def _agent_from_row(self, row: Any) -> AgentRecord:
        return AgentRecord(
            id=str(row["id"]),
            name=row["name"],
            description=row["description"],
            systemPrompt=row["system_prompt"],
            model=row["model"],
            allowedTools=_json_or_default(row["allowed_tools"], []),
            allowedKbs=_json_or_default(row["allowed_kbs"], []),
            chatOptions=_json_or_default(row["chat_options"], {}),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    def _chat_session_from_row(self, row: Any) -> ChatSessionRecord:
        return ChatSessionRecord(
            id=str(row["id"]),
            agentId=str(row["agent_id"]),
            title=row["title"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    def _chat_message_from_row(self, row: Any) -> ChatMessageRecord:
        return ChatMessageRecord(
            id=str(row["id"]),
            sessionId=str(row["session_id"]),
            role=row["role"],
            content=row["content"],
            metadata=_json_or_default(row["metadata"], None),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    def _knowledge_base_from_row(self, row: Any) -> KnowledgeBaseRecord:
        return KnowledgeBaseRecord(
            id=str(row["id"]),
            name=row["name"],
            description=row["description"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    def _document_from_row(self, row: Any) -> DocumentRecord:
        return DocumentRecord(
            id=str(row["id"]),
            kbId=str(row["kb_id"]),
            filename=row["filename"],
            filetype=row["filetype"],
            size=row["size"],
            metadata=_json_or_default(row["metadata"], None),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

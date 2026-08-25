from __future__ import annotations

from collections import defaultdict
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from fastapi import UploadFile

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


class InMemoryStore:
    def __init__(self) -> None:
        self.agents: dict[str, AgentRecord] = {}
        self.chat_sessions: dict[str, ChatSessionRecord] = {}
        self.chat_messages: dict[str, ChatMessageRecord] = {}
        self.chat_message_order: dict[str, list[str]] = defaultdict(list)
        self.knowledge_bases: dict[str, KnowledgeBaseRecord] = {}
        self.documents: dict[str, DocumentRecord] = {}

    async def health(self) -> dict[str, Any]:
        return {"configured": False, "connected": False, "mode": "memory"}

    async def list_agents(self) -> list[AgentRecord]:
        return list(self.agents.values())

    async def create_agent(self, request: CreateAgentRequest) -> AgentRecord:
        agent = AgentRecord(
            id=str(uuid4()),
            name=request.name,
            description=request.description,
            systemPrompt=request.system_prompt,
            model=request.model,
            allowedTools=request.allowed_tools,
            allowedKbs=request.allowed_kbs,
            chatOptions=request.chat_options.public_dict(),
        )
        self.agents[agent.id] = agent
        return agent

    async def delete_agent(self, agent_id: str) -> None:
        self.agents.pop(agent_id, None)

    async def update_agent(self, agent_id: str, request: UpdateAgentRequest) -> None:
        existing = self.agents.get(agent_id)
        if not existing:
            return
        data = existing.model_dump()
        for key, value in request.model_dump(exclude_unset=True).items():
            if value is not None:
                data[key] = value.public_dict() if hasattr(value, "public_dict") else value
        self.agents[agent_id] = AgentRecord(**data)

    async def list_chat_sessions(
        self, agent_id: str | None = None
    ) -> list[ChatSessionRecord]:
        sessions = list(self.chat_sessions.values())
        if agent_id:
            sessions = [item for item in sessions if item.agent_id == agent_id]
        return sessions

    async def get_chat_session(self, chat_session_id: str) -> ChatSessionRecord | None:
        return self.chat_sessions.get(chat_session_id)

    async def create_chat_session(
        self, request: CreateChatSessionRequest
    ) -> ChatSessionRecord:
        session = ChatSessionRecord(
            id=str(uuid4()), agentId=request.agent_id, title=request.title
        )
        self.chat_sessions[session.id] = session
        return session

    async def delete_chat_session(self, chat_session_id: str) -> None:
        self.chat_sessions.pop(chat_session_id, None)

    async def update_chat_session(
        self, chat_session_id: str, request: UpdateChatSessionRequest
    ) -> None:
        existing = self.chat_sessions.get(chat_session_id)
        if existing and request.title is not None:
            self.chat_sessions[chat_session_id] = existing.model_copy(
                update={"title": request.title}
            )

    async def list_chat_messages(self, session_id: str) -> list[ChatMessageRecord]:
        return [
            self.chat_messages[message_id]
            for message_id in self.chat_message_order.get(session_id, [])
            if message_id in self.chat_messages
        ]

    async def create_chat_message(
        self, request: CreateChatMessageRequest
    ) -> ChatMessageRecord:
        now = datetime.now(UTC)
        message = ChatMessageRecord(
            id=str(uuid4()),
            sessionId=request.session_id,
            role=request.role,
            content=request.content,
            metadata=request.metadata,
            createdAt=now,
            updatedAt=now,
        )
        self.chat_messages[message.id] = message
        self.chat_message_order[message.session_id].append(message.id)
        return message

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
        message = self.chat_messages.pop(chat_message_id, None)
        if message and message.id in self.chat_message_order.get(message.session_id, []):
            self.chat_message_order[message.session_id].remove(message.id)

    async def update_chat_message(
        self, chat_message_id: str, request: UpdateChatMessageRequest
    ) -> None:
        existing = self.chat_messages.get(chat_message_id)
        if not existing:
            return
        updates = request.model_dump(exclude_unset=True)
        self.chat_messages[chat_message_id] = existing.model_copy(update=updates)

    async def list_knowledge_bases(self) -> list[KnowledgeBaseRecord]:
        return list(self.knowledge_bases.values())

    async def create_knowledge_base(
        self, request: CreateKnowledgeBaseRequest
    ) -> KnowledgeBaseRecord:
        knowledge_base = KnowledgeBaseRecord(
            id=str(uuid4()), name=request.name, description=request.description
        )
        self.knowledge_bases[knowledge_base.id] = knowledge_base
        return knowledge_base

    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        self.knowledge_bases.pop(knowledge_base_id, None)

    async def update_knowledge_base(
        self, knowledge_base_id: str, request: UpdateKnowledgeBaseRequest
    ) -> None:
        existing = self.knowledge_bases.get(knowledge_base_id)
        if not existing:
            return
        updates = request.model_dump(exclude_unset=True)
        self.knowledge_bases[knowledge_base_id] = existing.model_copy(update=updates)

    async def list_documents(self, kb_id: str | None = None) -> list[DocumentRecord]:
        documents = list(self.documents.values())
        if kb_id:
            documents = [item for item in documents if item.kb_id == kb_id]
        return documents

    async def create_document(self, request: CreateDocumentRequest) -> DocumentRecord:
        document = DocumentRecord(
            id=str(uuid4()),
            kbId=request.kb_id,
            filename=request.filename,
            filetype=request.filetype,
            size=request.size,
            metadata=request.metadata,
        )
        self.documents[document.id] = document
        return document

    async def create_uploaded_document(
        self, kb_id: str, file: UploadFile
    ) -> DocumentRecord:
        content = await file.read()
        return await self.create_document(
            CreateDocumentRequest(
                kbId=kb_id,
                filename=file.filename or "upload",
                filetype=(file.content_type or "application/octet-stream"),
                size=len(content),
                metadata={},
            )
        )

    async def delete_document(self, document_id: str) -> None:
        self.documents.pop(document_id, None)

    async def update_document(
        self, document_id: str, request: UpdateDocumentRequest
    ) -> None:
        existing = self.documents.get(document_id)
        if not existing:
            return
        updates = request.model_dump(exclude_unset=True)
        self.documents[document_id] = existing.model_copy(update=updates)

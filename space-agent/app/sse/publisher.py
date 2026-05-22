from __future__ import annotations

import asyncio
import json
from collections import defaultdict
from typing import AsyncIterator


class InMemorySsePublisher:
    def __init__(self) -> None:
        self._queues: dict[str, set[asyncio.Queue[dict[str, object]]]] = defaultdict(set)

    async def send(self, chat_session_id: str, message: dict[str, object]) -> None:
        for queue in list(self._queues.get(chat_session_id, set())):
            await queue.put(message)

    async def stream(self, chat_session_id: str) -> AsyncIterator[str]:
        queue: asyncio.Queue[dict[str, object]] = asyncio.Queue()
        self._queues[chat_session_id].add(queue)
        try:
            yield "event: init\ndata: connected\n\n"
            while True:
                message = await queue.get()
                yield f"event: message\ndata: {json.dumps(message, ensure_ascii=False)}\n\n"
        finally:
            self._queues[chat_session_id].discard(queue)

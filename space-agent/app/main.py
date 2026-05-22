from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router, sse_router
from app.agent.runtime.stub import StubAgentRuntime
from app.core.config import Settings
from app.core.database import build_store
from app.sse.publisher import InMemorySsePublisher


def create_app(testing: bool = False) -> FastAPI:
    settings = Settings.for_tests() if testing else Settings()
    store = build_store(settings, testing=testing)
    sse_publisher = InMemorySsePublisher()
    agent_runtime = StubAgentRuntime(store=store, sse_publisher=sse_publisher)

    app = FastAPI(title="space-agent", version="0.1.0")
    app.state.settings = settings
    app.state.store = store
    app.state.sse_publisher = sse_publisher
    app.state.agent_runtime = agent_runtime

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health")
    async def health() -> dict[str, object]:
        database = await store.health()
        return {"status": "ok", "database": database}

    app.include_router(api_router, prefix="/api")
    app.include_router(sse_router, prefix="/sse")
    return app


app = create_app()

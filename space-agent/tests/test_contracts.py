from fastapi.testclient import TestClient

from app.agent.ports.runtime import AgentRuntimePort
from app.main import create_app


def test_settings_defaults_and_health_contract():
    app = create_app(testing=True)
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert "database" in response.json()
    assert app.state.settings.host == "0.0.0.0"
    assert app.state.settings.port == 8080
    assert app.state.settings.stub_agent is True


def test_crud_routes_keep_java_response_wrapper_contract():
    app = create_app(testing=True)
    client = TestClient(app)

    agent_response = client.post(
        "/api/agents",
        json={
            "name": "Research Agent",
            "description": "answers questions",
            "systemPrompt": "Be concise.",
            "model": "deepseek-chat",
            "allowedTools": ["directAnswer"],
            "allowedKbs": [],
            "chatOptions": {"temperature": 0.7, "topP": 1.0, "messageLength": 10},
        },
    )
    assert agent_response.status_code == 200
    assert agent_response.json()["code"] == 200
    agent_id = agent_response.json()["data"]["agentId"]

    agents = client.get("/api/agents").json()
    assert agents == {
        "code": 200,
        "message": "success",
        "data": {
            "agents": [
                {
                    "id": agent_id,
                    "name": "Research Agent",
                    "description": "answers questions",
                    "systemPrompt": "Be concise.",
                    "model": "deepseek-chat",
                    "allowedTools": ["directAnswer"],
                    "allowedKbs": [],
                    "chatOptions": {
                        "temperature": 0.7,
                        "topP": 1.0,
                        "messageLength": 10,
                    },
                }
            ]
        },
    }

    session_response = client.post(
        "/api/chat-sessions",
        json={"agentId": agent_id, "title": "Migration smoke"},
    ).json()
    assert session_response["code"] == 200
    session_id = session_response["data"]["chatSessionId"]

    message_response = client.post(
        "/api/chat-messages",
        json={
            "agentId": agent_id,
            "sessionId": session_id,
            "role": "user",
            "content": "hello",
        },
    ).json()
    assert message_response["code"] == 200

    messages = client.get(f"/api/chat-messages/session/{session_id}").json()
    assert messages["code"] == 200
    assert [item["role"] for item in messages["data"]["chatMessages"]] == [
        "user",
        "assistant",
    ]
    assert messages["data"]["chatMessages"][1]["content"].startswith(
        "[stub-agent]"
    )


def test_static_tools_and_port_boundary_are_available():
    app = create_app(testing=True)
    client = TestClient(app)

    tools = client.get("/api/tools").json()

    assert tools["code"] == 200
    assert {tool["name"] for tool in tools["data"]} >= {
        "terminate",
        "directAnswer",
    }
    assert isinstance(app.state.agent_runtime, AgentRuntimePort)

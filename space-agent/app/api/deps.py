from fastapi import Request


def get_store(request: Request):
    return request.app.state.store


def get_agent_runtime(request: Request):
    return request.app.state.agent_runtime


def get_sse_publisher(request: Request):
    return request.app.state.sse_publisher

from typing import Any


def success(data: Any = None, message: str = "success") -> dict[str, Any]:
    return {"code": 200, "message": message, "data": data}


def error(message: str = "error", code: int = 500) -> dict[str, Any]:
    return {"code": code, "message": message, "data": None}

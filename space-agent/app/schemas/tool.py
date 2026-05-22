from typing import Literal

from app.schemas.base import ApiModel


class ToolRecord(ApiModel):
    name: str
    description: str
    type: Literal["FIXED", "OPTIONAL"]

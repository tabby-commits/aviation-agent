from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str | None = Field(default=None, alias="SPACE_AGENT_DATABASE_URL")
    host: str = Field(default="0.0.0.0", alias="SPACE_AGENT_HOST")
    port: int = Field(default=8080, alias="SPACE_AGENT_PORT")
    stub_agent: bool = Field(default=True, alias="SPACE_AGENT_STUB_AGENT")

    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",
        populate_by_name=True,
    )

    @classmethod
    def for_tests(cls) -> "Settings":
        return cls(
            SPACE_AGENT_DATABASE_URL=None,
            SPACE_AGENT_HOST="0.0.0.0",
            SPACE_AGENT_PORT=8080,
            SPACE_AGENT_STUB_AGENT=True,
        )

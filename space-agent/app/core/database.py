from app.core.config import Settings
from app.repositories.memory import InMemoryStore
from app.repositories.sqlalchemy_store import SqlAlchemyStore


def build_store(settings: Settings, testing: bool = False):
    if testing or not settings.database_url:
        return InMemoryStore()
    return SqlAlchemyStore(settings.database_url)

"""Read-only access to the isolated AgentCraft legacy database."""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Callable

import mysql.connector

#从 `agentcraft_legacy` MySQL 库读取知识库分片数据，
#**强制约束账号仅具备只读权限，禁止写操作**；读取出来的数据会封装为强类型数据对象 `LegacyChunk`

LEGACY_DATABASE = "agentcraft_legacy"

#承载一条分片记录
@dataclass(frozen=True)
class LegacyChunk:
    chunk_id: int
    doc_id: int
    chunk_index: int
    text: str
    source_name: str
    page: int | None
    orphan: bool


class LegacyReaderError(ValueError):
    pass

#承载一条分片记录
class LegacyReader:
    def __init__(self, *, connect_factory: Callable[..., object] | None = None, **connection_args: object) -> None:
        database = str(connection_args.get("database", ""))
        if database != LEGACY_DATABASE:
            raise LegacyReaderError(f"legacy reader requires database {LEGACY_DATABASE}")
        self._connection_args = dict(connection_args)
        self._connect_factory = connect_factory or mysql.connector.connect
        self.last_stats: dict[str, int] = {}

#`from_env()` 类方法：从环境变量加载数据库连接配置
    @classmethod
    def from_env(cls) -> "LegacyReader":
        database = os.getenv("LEGACY_MYSQL_DATABASE", LEGACY_DATABASE)
        password = os.getenv("LEGACY_MYSQL_PASSWORD")
        if password is None:
            raise LegacyReaderError("LEGACY_MYSQL_PASSWORD must be set")
        return cls(
            host=os.getenv("LEGACY_MYSQL_HOST", "127.0.0.1"),
            port=int(os.getenv("LEGACY_MYSQL_PORT", "3306")),
            database=database,
            user=os.getenv("LEGACY_MYSQL_USER", "doctrail_legacy_reader"),
            password=password,
            connection_timeout=10,
        )

#`read_chunks()`：核心读取方法，查询分片并返回对象列表
    def read_chunks(self) -> list[LegacyChunk]:
        connection = self._connect_factory(**self._connection_args)
        cursor = connection.cursor()
        try:
            self._assert_read_only(cursor)
            cursor.execute("SELECT COUNT(*) FROM knowledge_doc")
            count_row = cursor.fetchone()
            self.last_stats["knowledge_doc_count"] = int(count_row[0])
            cursor.execute(
                """SELECT kc.id, kc.doc_id, kc.chunk_index, kc.chunk_text, kc.page_number,
                          kd.id, kd.doc_name
                   FROM knowledge_chunk kc
                   LEFT JOIN knowledge_doc kd ON kd.id = kc.doc_id
                   ORDER BY kc.id"""
            )
            chunks = [self._map_row(row) for row in cursor.fetchall()]
            chunk_ids = [chunk.chunk_id for chunk in chunks]
            if len(chunk_ids) != len(set(chunk_ids)):
                raise LegacyReaderError("legacy knowledge_chunk contains duplicate ids")
            return chunks
        finally:
            cursor.close()
            connection.close()

#_assert_read_only()：断言数据库连接仅具备只读权限
    @staticmethod
    def _assert_read_only(cursor: object) -> None:
        cursor.execute("SHOW GRANTS")
        import re
        grants = [str(row[0]).strip() for row in cursor.fetchall()]
        if not grants:
            raise LegacyReaderError("legacy reader has no grants")
        has_legacy_select = False
        for grant in grants:
            account = r"(?:`[^`]+`|'[^']+'|[A-Za-z0-9_%.-]+)"
            match = re.fullmatch(
                rf"GRANT\s+(.+?)\s+ON\s+(`?)([^`\s.]+)\2\s*\.\s*(`?)([^`\s]+)\4\s+TO\s+{account}\s*@\s*{account}",
                grant, flags=re.IGNORECASE,
            )
            if not match:
                raise LegacyReaderError("legacy reader contains an unexpected grant")
            privileges, database, table = match.group(1), match.group(3), match.group(5)
            privilege_set = {p.strip().upper() for p in privileges.split(",")}
            if database == "*" and table == "*" and privilege_set == {"USAGE"}:
                continue
            if database.lower() == LEGACY_DATABASE and table == "*" and privilege_set == {"SELECT"}:
                has_legacy_select = True
                continue
            raise LegacyReaderError("legacy reader must have only USAGE on *.* and SELECT on agentcraft_legacy.*")
        if not has_legacy_select:
            raise LegacyReaderError("legacy reader has no SELECT privilege on agentcraft_legacy.*")

#数据库行记录映射转换器
    @staticmethod
    def _map_row(row: tuple[object, ...]) -> LegacyChunk:
        if len(row) < 7:
            raise LegacyReaderError("legacy query returned an incomplete row")
        try:
            chunk_id, doc_id = int(row[0]), int(row[1])
            chunk_index = int(row[2])
        except (TypeError, ValueError) as exc:
            raise LegacyReaderError("legacy chunk identifiers must be integers") from exc
        if chunk_id <= 0 or doc_id <= 0 or chunk_index < 0:
            raise LegacyReaderError("legacy chunk identifiers must be positive")
        text = "" if row[3] is None else str(row[3])
        if not text.strip():
            raise LegacyReaderError(f"legacy chunk {chunk_id} has empty chunk_text")
        orphan = row[5] is None
        source_name = str(row[6]) if not orphan and row[6] else f"legacy-doc-{doc_id}"
        page = None if row[4] is None else int(row[4])
        if page is not None and page < 0:
            raise LegacyReaderError(f"legacy chunk {chunk_id} has an invalid page number")
        return LegacyChunk(chunk_id, doc_id, chunk_index, text, source_name, page, orphan)


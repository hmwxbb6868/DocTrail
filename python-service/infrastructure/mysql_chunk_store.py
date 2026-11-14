import os
from dataclasses import dataclass
from typing import Callable, Protocol
from uuid import UUID, uuid4

import mysql.connector
from mysql.connector import Error

from core.text_splitter import TextChunk

""" **MySQL 分片存储（ChunkStore）** 的 Python 实现，负责把文档切分后的文本片段（TextChunk）存入 MySQL，
并且实现**覆盖更新**逻辑：同一篇文档上传新的分片时，旧分片标记为失效，新分片写入 """

#分片持久化异常
class ChunkPersistenceError(RuntimeError):
    pass

#纯数据载体（DTO），只用来装结果信息，没有业务逻辑
@dataclass(frozen=True)
class ChunkBatch:
    batch_id: UUID #本次文档分片操作的唯一批次号，版本区分，同一个文档不同次切分的batch_id不同
    chunk_count: int # 本次一共存了多少个分片
    previous_batch_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class DeletedChunkBatch:
    doc_id: int
    deleted_chunk_count: int
    previous_active_batch_ids: tuple[str, ...] = ()


#用户提问，向量库检索，拿到匹配向量的 `batch_id + chunk_index`，
#拿着这两个字段去 MySQL 查询，**只查 active=1 的分片**
#把查询结果封装成 `ActiveChunk` 对象返回给大模型
@dataclass(frozen=True)
class ActiveChunk:
    chunk_id: int
    doc_id: int
    batch_id: UUID
    chunk_index: int
    content: str
    doc_name: str | None = None

#给find_active_batch做返回的对象
@dataclass(frozen=True)
class ActiveDocumentBatch:
    """The single active batch that can be safely reused for an idempotent parse."""

    doc_id: int
    content_hash: str
    batch_id: UUID
    chunk_count: int

#规定所有 “分片存储器” 必须实现 `replace_active_chunks` 方法。
class ChunkStore(Protocol):
    def replace_active_chunks(self, doc_id: int, chunks: list[TextChunk]) -> ChunkBatch: ...

    #查【单个文档】当前的活跃批次元信息（ActiveDocumentBatch）
    def find_active_batch(self, doc_id: int, content_hash: str) -> ActiveDocumentBatch | None: ...

    #查【全局所有文档】全部 active=1 的分片
    def list_active_chunks(self) -> list[ActiveChunk]: ...

    def restore_previous_active_chunks(self, doc_id: int, batch: ChunkBatch) -> None: ...

    def delete_inactive_chunks(self, doc_id: int, keep_batch_id: UUID) -> None: ...

    def deactivate_document_chunks(self, doc_id: int) -> DeletedChunkBatch: ...

    def restore_document_chunks(self, batch: DeletedChunkBatch) -> None: ...

    def purge_inactive_document_chunks(self, doc_id: int) -> None: ...


class MysqlChunkStore:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        database: str,
        user: str,
        password: str,
        connection_timeout: int = 10,
        connect_factory: Callable[..., object] | None = None,#数据库连接工厂函数，可以传一个自定义函数；默认是`None`
    ) -> None:
        self._connection_args = {
            "host": host,
            "port": port,
            "database": database,
            "user": user,
            "password": password,
            "connection_timeout": connection_timeout,
        }
        self._connect_factory = connect_factory or mysql.connector.connect

#     替换一篇文档当前所有「生效的文本分片」
#    `chunks`：函数入参，是刚刚文档解析、文本切分得到的新 TextChunk 列表
    def replace_active_chunks(self, doc_id: int, chunks: list[TextChunk]) -> ChunkBatch:
        if doc_id <= 0:
            raise ChunkPersistenceError("doc_id must be positive")
        if not chunks:
            raise ChunkPersistenceError("document produced no chunks")

        batch_id = uuid4()
        connection = None #MySQL 数据库连接对象
        cursor = None #数据库游标对象
        try:
            # 创建数据库连接，开启事务
            connection = self._connect_factory(**self._connection_args)
            #建立一条到 MySQL 的连接
            connection.start_transaction()
            #数据库连接拿到**游标 cursor**
            cursor = connection.cursor()
            #`cursor.execute( sql, 参数元组 )`：执行 SQL 语句;`FOR UPDATE`：悲观行锁
            cursor.execute("SELECT id FROM knowledge_doc WHERE id = %s FOR UPDATE", (doc_id,))
            #`cursor.fetchone()`：取出上一条 SELECT 查询返回的**第一行结果**
            if cursor.fetchone() is None:
                raise ChunkPersistenceError("knowledge document does not exist")

            #这条查询给向量库用的
            #查询当前文档 doc_id 下，所有处于生效状态 (active=1) 的旧分片所属的全部批次 batch_id
            cursor.execute(
                "SELECT DISTINCT batch_id FROM knowledge_chunk WHERE doc_id = %s AND active = 1",
                (doc_id,),
            )
            #收集起来保存到 `previous_batch_ids`：旧 batch_id 的元组，用于返回给向量库
            previous_batch_ids = tuple(str(row[0]) for row in cursor.fetchall())

            #把该文档目前正在使用的所有旧分片，全部标记成失效。
            cursor.execute(
                "UPDATE knowledge_chunk SET active = 0 WHERE doc_id = %s AND active = 1",
                (doc_id,),
            )

            #遍历传入的所有 `TextChunk`，把每一个分片对象，转换成一条可以插入 MySQL 的元组数据，最后把所有元组放到列表 `rows` 里。
            rows = [(doc_id, str(batch_id), chunk.index, chunk.content) for chunk in chunks]
            #批量插入所有分片数据
            cursor.executemany(
                """
                INSERT INTO knowledge_chunk
                    (doc_id, batch_id, chunk_index, content, active)
                VALUES (%s, %s, %s, %s, 1)
                """,
                rows,
            )
            #提交事务
            connection.commit()
            #返回 ChunkBatch 对象，包含批次号和分片数量
            return ChunkBatch(
                batch_id=batch_id,
                chunk_count=len(rows),
                previous_batch_ids=previous_batch_ids,
            )
        except ChunkPersistenceError:
            _rollback(connection)
            raise
        except (Error, OSError, RuntimeError) as exc:
            _rollback(connection)
            raise ChunkPersistenceError("knowledge_chunk transaction failed") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #查【单个文档】当前的活跃批次元信息（ActiveDocumentBatch）
    def find_active_batch(self, doc_id: int, content_hash: str) -> ActiveDocumentBatch | None:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            cursor = connection.cursor()
            cursor.execute(
                """
                SELECT kd.content_hash, kc.batch_id, COUNT(*)
                FROM knowledge_doc kd
                JOIN knowledge_chunk kc ON kc.doc_id = kd.id AND kc.active = 1
                WHERE kd.id = %s AND kd.content_hash = %s
                GROUP BY kd.content_hash, kc.batch_id
                ORDER BY kc.batch_id
                """,
                (doc_id, content_hash),
            )
            rows = cursor.fetchall()
            if not rows:
                return None
            if len(rows) != 1:
                raise ChunkPersistenceError(
                    "document has multiple active batches and cannot be reused safely"
                )
            row = rows[0]
            return ActiveDocumentBatch(
                doc_id=doc_id,
                content_hash=str(row[0]),
                batch_id=UUID(str(row[1])),
                chunk_count=int(row[2]),
            )
        except ChunkPersistenceError:
            raise
        except (Error, OSError, RuntimeError, ValueError) as exc:
            raise ChunkPersistenceError("failed to inspect active document batch") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #查询所有 `active = 1` 的分片，组装成 `ActiveChunk` 对象列表返回
    def list_active_chunks(self) -> list[ActiveChunk]:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            cursor = connection.cursor()
            cursor.execute(
                """
                SELECT kc.id, kc.doc_id, kc.batch_id, kc.chunk_index, kc.content, kd.doc_name
                FROM knowledge_chunk kc
                JOIN knowledge_doc kd ON kd.id = kc.doc_id
                WHERE kc.active = 1
                ORDER BY kc.id
                """
            )
            return [
                ActiveChunk(
                    chunk_id=int(row[0]),
                    doc_id=int(row[1]),
                    batch_id=UUID(str(row[2])),
                    chunk_index=int(row[3]),
                    content=str(row[4]),
                    doc_name=str(row[5]) if row[5] is not None else None,
                )
                #`fetchall()`：一次性取出 SQL 返回的「所有行」，返回一个列表。
                for row in cursor.fetchall()
            ]
        except (Error, OSError, RuntimeError, ValueError) as exc:
            raise ChunkPersistenceError("failed to read active knowledge chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #文档分片版本回滚函数：把文档 `doc_id` 当前正在生效的批次停用，重新启用【之前被废弃的旧批次】，实现知识库分片版本回退。
    #ChunkBatch：更新 `replace_active_chunks` 之后返回的那个 ChunkBatch 对象。
    def restore_previous_active_chunks(self, doc_id: int, batch: ChunkBatch) -> None:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            connection.start_transaction()
            cursor = connection.cursor()

            #`FOR UPDATE`，加行排他锁：防止并发同时对同一个文档执行更新 / 回滚
            cursor.execute("SELECT id FROM knowledge_doc WHERE id = %s FOR UPDATE", (doc_id,))
            if cursor.fetchone() is None:
                raise ChunkPersistenceError("knowledge document does not exist")

            #从数据库更新，把当前批次的分片标记为失效
            cursor.execute(
                "UPDATE knowledge_chunk SET active = 0 WHERE doc_id = %s AND batch_id = %s",
                (doc_id, str(batch.batch_id)),
            )
            #如果存在旧批次，则启用这些旧批次的分片
            if batch.previous_batch_ids:
                #动态生成 SQL 占位符字符串
                placeholders = ", ".join(["%s"] * len(batch.previous_batch_ids))
                cursor.execute(
                    f"UPDATE knowledge_chunk SET active = 1 WHERE doc_id = %s AND batch_id IN ({placeholders})",
                    (doc_id, *batch.previous_batch_ids),
                )
            connection.commit()
        except ChunkPersistenceError:
             #执行数据库回滚
            _rollback(connection)
            raise
        except (Error, OSError, RuntimeError) as exc:
            #执行数据库回滚
            _rollback(connection)
            raise ChunkPersistenceError("failed to restore previous active chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    def delete_inactive_chunks(self, doc_id: int, keep_batch_id: UUID) -> None:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            cursor = connection.cursor()
            cursor.execute(
                "DELETE FROM knowledge_chunk WHERE doc_id = %s AND active = 0 AND batch_id <> %s",
                (doc_id, str(keep_batch_id)),
            )
            connection.commit()
        except (Error, OSError, RuntimeError) as exc:
            #执行数据库回滚
            _rollback(connection)
            raise ChunkPersistenceError("failed to clean inactive knowledge chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #做索引构建前，软失效文档下所有活跃分片
    def deactivate_document_chunks(self, doc_id: int) -> DeletedChunkBatch:
        #doc_id,  deleted_chunk_count,  previous_active_batch_ids: tuple[str, ...]
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            connection.start_transaction()
            cursor = connection.cursor()
            cursor.execute("SELECT id FROM knowledge_doc WHERE id = %s FOR UPDATE", (doc_id,))
            if cursor.fetchone() is None:
                raise ChunkPersistenceError("knowledge document does not exist")
            cursor.execute(
                "SELECT DISTINCT batch_id FROM knowledge_chunk WHERE doc_id = %s AND active = 1",
                (doc_id,),
            )
            #`batch_id`（UUID 对象）转成字符串
            previous_active_batch_ids = tuple(str(row[0]) for row in cursor.fetchall())
            cursor.execute(
                "UPDATE knowledge_chunk SET active = 0 WHERE doc_id = %s AND active = 1",
                (doc_id,),
            )
            deleted_count = int(cursor.rowcount)
            connection.commit()
            return DeletedChunkBatch(
                doc_id=doc_id,
                deleted_chunk_count=deleted_count,
                previous_active_batch_ids=previous_active_batch_ids,
            )
        except ChunkPersistenceError:
            _rollback(connection)
            raise
        except (Error, OSError, RuntimeError) as exc:
            _rollback(connection)
            raise ChunkPersistenceError("failed to deactivate document chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #新版向量索引构建失败，无法切换索引，向量库还保留文档 A 向量。所以要执行 restore，
    #把刚才软失效的分片重新激活，**修复 MySQL 与向量库的数据不一致问题**。
    def restore_document_chunks(self, batch: DeletedChunkBatch) -> None:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            connection.start_transaction()
            cursor = connection.cursor()
            if batch.previous_active_batch_ids:
                placeholders = ", ".join(["%s"] * len(batch.previous_active_batch_ids))
                cursor.execute(
                    f"UPDATE knowledge_chunk SET active = 1 "
                    f"WHERE doc_id = %s AND active = 0 AND batch_id IN ({placeholders})",
                    (batch.doc_id, *batch.previous_active_batch_ids),
                )
            connection.commit()
        except (Error, OSError, RuntimeError) as exc:
            _rollback(connection)
            raise ChunkPersistenceError("failed to restore document chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()

    #新版向量索引构建并且激活成功之后。删除文档下所有失效分片
    def purge_inactive_document_chunks(self, doc_id: int) -> None:
        connection = None
        cursor = None
        try:
            connection = self._connect_factory(**self._connection_args)
            cursor = connection.cursor()
            cursor.execute(
                "DELETE FROM knowledge_chunk WHERE doc_id = %s AND active = 0",
                (doc_id,),
            )
            connection.commit()
        except (Error, OSError, RuntimeError) as exc:
            _rollback(connection)
            raise ChunkPersistenceError("failed to purge deleted document chunks") from exc
        finally:
            if cursor is not None:
                cursor.close()
            if connection is not None:
                connection.close()


def create_chunk_store_from_env() -> MysqlChunkStore:
    return MysqlChunkStore(
        host=os.getenv("MYSQL_HOST", "127.0.0.1").strip(),
        port=_integer_env("MYSQL_PORT", 3306),
        database=os.getenv("MYSQL_DATABASE", "doctrail").strip(),
        user=os.getenv("MYSQL_USERNAME", "doctrail").strip(),
        password=os.getenv("MYSQL_PASSWORD", ""),
        connection_timeout=_integer_env("MYSQL_CONNECT_TIMEOUT_SECONDS", 10),
    )


def _integer_env(name: str, default: int) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ChunkPersistenceError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ChunkPersistenceError(f"{name} must be positive")
    return value

#事务回滚，撤销当前事务里所有还没`commit`的数据库修改
def _rollback(connection: object | None) -> None:
    if connection is None:
        return
    try:
        #撤销这一次事务里所有改动，数据库恢复到事务开始之前的样子
        connection.rollback()
    except (Error, OSError, RuntimeError):
        pass

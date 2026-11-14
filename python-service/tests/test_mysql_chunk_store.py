from dataclasses import dataclass

import pytest

from core.text_splitter import TextChunk
from infrastructure.mysql_chunk_store import ChunkPersistenceError, MysqlChunkStore


@dataclass
class FakeCursor:
    document_exists: bool = True
    fail_on_insert: bool = False
    fetchall_rows: list[tuple] | None = None
    committed_rows: list[tuple] | None = None
    statements: list[tuple[str, tuple | list[tuple]]] | None = None

    def __post_init__(self) -> None:
        self.committed_rows = [] if self.committed_rows is None else self.committed_rows
        self.fetchall_rows = [] if self.fetchall_rows is None else self.fetchall_rows
        self.statements = [] if self.statements is None else self.statements

    def execute(self, sql: str, params: tuple) -> None:
        self.statements.append((sql, params))

    def fetchone(self):
        return (46,) if self.document_exists else None

    def fetchall(self):
        return self.fetchall_rows

    def executemany(self, sql: str, rows: list[tuple]) -> None:
        if self.fail_on_insert:
            raise RuntimeError("insert failed")
        self.statements.append((sql, rows))
        self.committed_rows.extend(rows)

    def close(self) -> None:
        pass


class FakeConnection:
    def __init__(self, cursor: FakeCursor) -> None:
        self.cursor_value = cursor
        self.started = False
        self.committed = False
        self.rolled_back = False
        self.closed = False

    def start_transaction(self) -> None:
        self.started = True

    def cursor(self) -> FakeCursor:
        return self.cursor_value

    def commit(self) -> None:
        self.committed = True

    def rollback(self) -> None:
        self.rolled_back = True

    def close(self) -> None:
        self.closed = True


def test_mysql_chunk_store_replaces_active_rows_in_one_transaction() -> None:
    cursor = FakeCursor()
    connection = FakeConnection(cursor)
    store = MysqlChunkStore(
        host="localhost",
        port=3306,
        database="doctrail",
        user="doctrail",
        password="",
        connect_factory=lambda **_: connection,
    )

    result = store.replace_active_chunks(
        46,
        [TextChunk(0, "first"), TextChunk(1, "second")],
    )

    assert result.chunk_count == 2
    assert result.batch_id
    assert connection.started is True
    assert connection.committed is True
    assert connection.rolled_back is False
    assert connection.closed is True
    assert cursor.committed_rows[0][:3] == (46, str(result.batch_id), 0)
    assert cursor.committed_rows[1][:3] == (46, str(result.batch_id), 1)


def test_mysql_chunk_store_rolls_back_when_document_is_missing() -> None:
    cursor = FakeCursor(document_exists=False)
    connection = FakeConnection(cursor)
    store = MysqlChunkStore(
        host="localhost",
        port=3306,
        database="doctrail",
        user="doctrail",
        password="",
        connect_factory=lambda **_: connection,
    )

    with pytest.raises(ChunkPersistenceError, match="does not exist"):
        store.replace_active_chunks(46, [TextChunk(0, "first")])

    assert connection.committed is False
    assert connection.rolled_back is True


def test_mysql_chunk_store_rolls_back_when_chunk_insert_fails() -> None:
    cursor = FakeCursor(fail_on_insert=True)
    connection = FakeConnection(cursor)
    store = MysqlChunkStore(
        host="localhost",
        port=3306,
        database="doctrail",
        user="doctrail",
        password="",
        connect_factory=lambda **_: connection,
    )

    with pytest.raises(ChunkPersistenceError, match="transaction failed"):
        store.replace_active_chunks(46, [TextChunk(0, "first")])

    assert connection.committed is False
    assert connection.rolled_back is True


def test_mysql_chunk_store_rejects_empty_chunks() -> None:
    store = MysqlChunkStore(
        host="localhost",
        port=3306,
        database="doctrail",
        user="doctrail",
        password="",
        connect_factory=lambda **_: FakeConnection(FakeCursor()),
    )

    with pytest.raises(ChunkPersistenceError, match="no chunks"):
        store.replace_active_chunks(46, [])


def test_mysql_chunk_store_returns_one_reusable_active_batch() -> None:
    batch_id = "11111111-1111-1111-1111-111111111111"
    cursor = FakeCursor(fetchall_rows=[("a" * 64, batch_id, 2)])
    connection = FakeConnection(cursor)
    store = MysqlChunkStore(
        host="localhost",
        port=3306,
        database="doctrail",
        user="doctrail",
        password="",
        connect_factory=lambda **_: connection,
    )

    result = store.find_active_batch(46, "a" * 64)

    assert result is not None
    assert result.doc_id == 46
    assert str(result.batch_id) == batch_id
    assert result.chunk_count == 2
    assert connection.closed is True

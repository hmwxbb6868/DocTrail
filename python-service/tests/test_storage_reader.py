from dataclasses import dataclass
from pathlib import Path

import pytest

from infrastructure.storage_reader import (
    LocalObjectReader,
    OssObjectReader,
    StoredObjectNotFoundError,
    StoredObjectReadError,
    StoredObjectTooLargeError,
)


def test_local_reader_uses_shared_root_prefix_and_relative_key(tmp_path: Path) -> None:
    target = tmp_path / "doctrail" / "2026" / "09" / "document.txt"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"content")

    reader = LocalObjectReader(tmp_path, "doctrail", max_bytes=1024)

    assert reader.read("2026/09/document.txt") == b"content"


def test_local_reader_rejects_missing_and_oversized_objects(tmp_path: Path) -> None:
    reader = LocalObjectReader(tmp_path, "doctrail", max_bytes=2)
    with pytest.raises(StoredObjectNotFoundError):
        reader.read("missing.txt")

    target = tmp_path / "doctrail" / "large.txt"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"large")
    with pytest.raises(StoredObjectTooLargeError):
        reader.read("large.txt")


def test_local_reader_rejects_symlink_escape(tmp_path: Path) -> None:
    prefix_root = tmp_path / "doctrail"
    prefix_root.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    link = prefix_root / "escape"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except (OSError, NotImplementedError) as exc:
        pytest.skip(f"symbolic links are unavailable: {exc}")
    (outside / "file.txt").write_bytes(b"secret")
    reader = LocalObjectReader(tmp_path, "doctrail", max_bytes=1024)
    with pytest.raises(StoredObjectReadError, match="escapes UPLOAD_ROOT"):
        reader.read("escape/file.txt")


@dataclass
class FakeMetadata:
    content_length: int


class FakeResult:
    def __init__(self, data: bytes) -> None:
        self._data = data

    def read(self) -> bytes:
        return self._data


class FakeBucket:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.keys: list[str] = []

    def head_object(self, key: str) -> FakeMetadata:
        self.keys.append(key)
        return FakeMetadata(len(self.data))

    def get_object(self, key: str) -> FakeResult:
        self.keys.append(key)
        return FakeResult(self.data)


class MisreportedSizeBucket(FakeBucket):
    def head_object(self, key: str) -> FakeMetadata:
        self.keys.append(key)
        return FakeMetadata(1)


def test_oss_reader_uses_normalized_prefix_and_read_only_calls() -> None:
    bucket = FakeBucket(b"content")
    reader = OssObjectReader(bucket, "/doctrail//", max_bytes=1024, max_retries=0)

    assert reader.read("2026//09/document.txt") == b"content"
    assert bucket.keys == ["doctrail/2026/09/document.txt"] * 2


def test_oss_reader_rechecks_downloaded_size() -> None:
    bucket = MisreportedSizeBucket(b"oversized")
    reader = OssObjectReader(bucket, "doctrail", max_bytes=2, max_retries=0)

    with pytest.raises(StoredObjectTooLargeError):
        reader.read("document.txt")

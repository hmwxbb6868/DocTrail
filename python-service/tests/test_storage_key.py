import pytest

from storage import StorageKeyError, normalize_object_key, normalize_relative_key


def test_storage_key_normalization_matches_object_key_contract() -> None:
    assert normalize_relative_key(" 2026//09/document.pdf ") == "2026/09/document.pdf"
    assert normalize_object_key("/doctrail//", "2026//09/document.pdf") == "doctrail/2026/09/document.pdf"


@pytest.mark.parametrize(
    "value",
    ["", "/absolute/file.pdf", "C:/uploads/file.pdf", "a\\b.pdf", "a/../b.pdf", "a/./b.pdf"],
)
def test_relative_storage_key_rejects_unsafe_paths(value: str) -> None:
    with pytest.raises(StorageKeyError):
        normalize_relative_key(value)


def test_prefix_must_not_contain_parent_segment() -> None:
    with pytest.raises(StorageKeyError):
        normalize_object_key("doctrail/../escape", "file.pdf")
    with pytest.raises(StorageKeyError):
        normalize_object_key("C:/uploads", "file.pdf")

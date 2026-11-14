from pathlib import Path
from argparse import Namespace
import json

import pytest

from infrastructure.legacy_reader import LegacyChunk, LegacyReader, LegacyReaderError
from scripts.rebuild_index import _safe_index_dir
from scripts import rebuild_index as script


def test_legacy_index_name_cannot_escape_directory(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        _safe_index_dir(tmp_path, "../formal")


def test_legacy_index_cannot_overlap_formal_index(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    formal = tmp_path / "faiss_index"
    monkeypatch.setenv("VECTOR_STORE_PERSIST_DIR", str(formal))
    with pytest.raises(ValueError):
        _safe_index_dir(tmp_path, "faiss_index")


def test_legacy_index_protection_is_independent_of_cwd(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("VECTOR_STORE_PERSIST_DIR", raising=False)
    repo = tmp_path / "DocTrail"
    service = repo / "python-service"
    service.mkdir(parents=True)
    monkeypatch.setattr(script, "PROJECT_ROOT", repo)
    monkeypatch.setattr(script, "SERVICE_ROOT", service)
    for cwd in [repo, service]:
        monkeypatch.chdir(cwd)
        with pytest.raises(ValueError):
            _safe_index_dir(Path("."), "faiss_index")
        with pytest.raises(ValueError):
            _safe_index_dir(repo, "faiss_index")
        assert _safe_index_dir(Path("../"), "faiss_index") != repo / "faiss_index"
        assert _safe_index_dir(Path("legacy-indexes"), "doctrail_legacy_reproduce") == repo / "legacy-indexes" / "doctrail_legacy_reproduce"


def test_explicit_formal_path_does_not_disable_environment_protection(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("VECTOR_STORE_PERSIST_DIR", str(tmp_path / "formal"))
    with pytest.raises(ValueError):
        _safe_index_dir(tmp_path, "formal", tmp_path / "other-formal")


class GrantCursor:
    def __init__(self, grants):
        self.grants = grants

    def execute(self, query):
        assert query == "SHOW GRANTS"

    def fetchall(self):
        return [(grant,) for grant in self.grants]


@pytest.mark.parametrize("target", ["`agentcraft_legacy`.*", "agentcraft_legacy.*", "`agentcraft_legacy` . *"])
def test_legacy_reader_accepts_quoted_and_unquoted_database_grants(target) -> None:
    LegacyReader._assert_read_only(GrantCursor([
        "GRANT USAGE ON *.* TO `reader`@`localhost`",
        f"GRANT SELECT ON {target} TO `reader`@`localhost`",
    ]))


@pytest.mark.parametrize("grant", [
    "GRANT SELECT ON `doctrail`.* TO `reader`@`localhost`",
    "GRANT SELECT ON *.* TO `reader`@`localhost`",
    "GRANT SELECT ON agentcraft_legacy.knowledge_chunk TO `reader`@`localhost`",
    "GRANT SELECT, INSERT ON agentcraft_legacy.* TO `reader`@`localhost`",
    "GRANT SELECT ON agentcraft_legacy.* TO `reader`@`localhost` WITH GRANT OPTION",
    "GRANT `role`@`localhost` TO `reader`@`localhost`",
    "GRANT ALL PRIVILEGES ON agentcraft_legacy.* TO `reader`@`localhost`",
])
def test_legacy_reader_rejects_global_other_database_write_role_or_grant_option(grant) -> None:
    with pytest.raises(LegacyReaderError):
        LegacyReader._assert_read_only(GrantCursor([
            "GRANT SELECT ON `agentcraft_legacy`.* TO `reader`@`localhost`", grant,
        ]))


def test_legacy_reader_rejects_select_on_formal_database() -> None:
    class Cursor:
        def execute(self, query: str) -> None: self.query = query
        def fetchall(self) -> list[tuple[object, ...]]:
            return [("GRANT USAGE ON *.* TO `reader`@`localhost`",),
                    ("GRANT SELECT ON `doctrail`.* TO `reader`@`localhost`",)]
    with pytest.raises(LegacyReaderError):
        LegacyReader._assert_read_only(Cursor())


def test_legacy_reader_rejects_non_legacy_database() -> None:
    with pytest.raises(LegacyReaderError):
        LegacyReader(database="doctrail", user="x", password="x")


def test_orphan_source_name_is_explicit() -> None:
    chunk = LegacyReader._map_row((1, 42, 0, "旧文档内容", None, None, None))
    assert chunk.orphan is True
    assert chunk.source_name == "legacy-doc-42"


def test_empty_legacy_text_is_rejected() -> None:
    with pytest.raises(LegacyReaderError):
        LegacyReader._map_row((1, 42, 0, "  ", None, 3, "doc"))


def test_invalid_page_is_rejected() -> None:
    with pytest.raises(LegacyReaderError):
        LegacyReader._map_row((1, 42, 0, "内容", -1, 3, "doc"))


def test_duplicate_legacy_chunk_ids_are_rejected() -> None:
    class Cursor:
        def execute(self, query: str) -> None:
            self.query = query

        def fetchone(self) -> tuple[int]:
            return (1,)

        def fetchall(self) -> list[tuple[object, ...]]:
            if self.query == "SHOW GRANTS":
                return [("GRANT USAGE ON *.* TO `reader`@`localhost`",),
                        ("GRANT SELECT ON `agentcraft_legacy`.* TO `reader`@`localhost`",)]
            return [(1, 42, 0, "a", None, 3, "doc"), (1, 42, 1, "b", None, 3, "doc")]

        def close(self) -> None:
            pass

    class Connection:
        def cursor(self) -> Cursor:
            return Cursor()

        def close(self) -> None:
            pass

    with pytest.raises(LegacyReaderError):
        LegacyReader(database="agentcraft_legacy", connect_factory=lambda **_: Connection()).read_chunks()


@pytest.fixture
def isolated_rebuild(tmp_path, monkeypatch):
    class Reader:
        last_stats = {"knowledge_doc_count": 1}

        def read_chunks(self):
            return [LegacyChunk(1, 32, 0, "孤儿正文", "legacy-doc-32", None, True),
                    LegacyChunk(2, 46, 0, "现存正文", "现存文档", 1, False)]

    class Provider:
        dimension = 2

        def __init__(self, *, model_name, cache_dir, normalized):
            self.model_name = model_name
            self.normalized = normalized

        def embed(self, texts):
            return [[1.0, 0.0] if text == "孤儿正文" else [0.0, 1.0] for text in texts]

    monkeypatch.setattr(script.LegacyReader, "from_env", lambda: Reader())
    monkeypatch.setattr(script, "HuggingFaceEmbeddingProvider", Provider)
    args = Namespace(source="legacy", database="agentcraft_legacy", index_name="doctrail_legacy_reproduce",
                     index_root=tmp_path / "indexes", formal_index_dir=tmp_path / "formal",
                     model="fake-model", cache_dir=tmp_path / "cache", top_k=1, query="查询", query_only=False)
    return args


def test_rebuild_publishes_structured_mapping_real_statistics_and_new_build_hits(isolated_rebuild):
    args = isolated_rebuild
    report = script.rebuild(args)
    root = args.index_root / args.index_name
    build = root / f"build-{report['index_build_id']}"
    mapping = json.loads((build / "legacy_sources.json").read_text(encoding="utf-8"))
    assert len(mapping["chunks"]) == 2
    assert mapping["index_build_id"] == report["index_build_id"]
    assert report["knowledge_doc_count"] == 1
    assert report["orphan_chunk_count"] == 1
    assert report["orphan_chunk_ratio"] == 0.5
    assert report["search_results"][0]["chunk_id"] == 2
    assert report["search_results"][0]["source_name"] == "现存文档"
    assert (build / "legacy_rebuild_report.json").is_file()
    assert not (root / "legacy_rebuild_report.json").exists()
    assert (root / "active.pointer").read_text() == report["index_build_id"]


@pytest.mark.parametrize("failure", ["search", "report"])
def test_failed_search_or_report_keeps_previous_pointer_and_build(isolated_rebuild, monkeypatch, failure):
    args = isolated_rebuild
    original = script.rebuild(args)
    root = args.index_root / args.index_name
    old_report = root / f"build-{original['index_build_id']}" / "legacy_rebuild_report.json"
    report_bytes = old_report.read_bytes()
    if failure == "search":
        def fail_search(*_args, **_kwargs):
            raise RuntimeError("retrieval failed")
        monkeypatch.setattr(script.FaissVectorStore, "search_build", fail_search)
    else:
        original_write = Path.write_text
        def fail_report(self, *values, **options):
            if self.name == "legacy_rebuild_report.json":
                raise OSError("report write failed")
            return original_write(self, *values, **options)
        monkeypatch.setattr(Path, "write_text", fail_report)
    with pytest.raises((RuntimeError, OSError)):
        script.rebuild(args)
    assert (root / "active.pointer").read_text() == original["index_build_id"]
    assert old_report.read_bytes() == report_bytes
    assert len(list(root.glob("build-*"))) == 1


def test_query_only_does_not_read_database_or_publish_new_build(isolated_rebuild, monkeypatch):
    args = isolated_rebuild
    report = script.rebuild(args)
    root = args.index_root / args.index_name
    before = {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*") if path.is_file()}
    def reject_database():
        raise AssertionError("query-only must not access MySQL")
    monkeypatch.setattr(script.LegacyReader, "from_env", reject_database)
    args.query_only = True
    result = script.rebuild(args)
    assert result["index_build_id"] == report["index_build_id"]
    assert result["results"][0]["page"] == 1
    after = {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*") if path.is_file()}
    assert before == after

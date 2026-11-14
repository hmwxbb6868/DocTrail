"""Rebuild an isolated FAISS index from the read-only legacy database."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path
from uuid import NAMESPACE_URL, uuid4, uuid5

#采用单入口双模式设计：可通过命令行参数切换「全量重建索引」或「仅查询已有索引」两种工作模式
#全程对 legacy 索引目录做安全隔离，防止操作覆盖线上正式业务 FAISS 索引
#安全隔离：legacy 索引文件夹和线上正式索引文件夹

#自动计算项目目录，把服务根目录加到 Python 模块搜索路径
SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SERVICE_ROOT.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from infrastructure.legacy_reader import LEGACY_DATABASE, LegacyReader
from infrastructure.faiss_vector_store import FaissVectorStore
from providers.embedding_provider import HuggingFaceEmbeddingProvider
from core.vector_store import IndexManifest, VectorMatch, VectorPoint

#负责解析你在命令行传入的参数，控制脚本行为。
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Rebuild an isolated legacy FAISS index.")
    parser.add_argument("--source", choices=("legacy",), required=True)
    parser.add_argument("--index-name", required=True)
    parser.add_argument("--index-root", type=Path, default=Path(os.getenv("LEGACY_INDEX_ROOT", "./legacy-indexes")))
    parser.add_argument("--formal-index-dir", type=Path, default=None)
    parser.add_argument("--database", default=os.getenv("LEGACY_MYSQL_DATABASE", LEGACY_DATABASE))
    parser.add_argument("--model", default=os.getenv("LOCAL_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5"))
    parser.add_argument("--cache-dir", type=Path, default=Path(os.getenv("MODEL_CACHE_DIR", "./model-cache")))
    parser.add_argument("--query", help="embed and validate a query before publishing, or use with --query-only")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--query-only", action="store_true", help="query the already active legacy build")
    return parser.parse_args()

#把相对路径转成基于项目根目录 PROJECT_ROOT 的绝对路径
def _repo_relative(path: Path) -> Path:
    return path if path.is_absolute() else PROJECT_ROOT / path

#校验 legacy 索引目录是否安全，防止覆盖线上正式 FAISS 索引
def _safe_index_dir(root: Path, name: str, formal_index_dir: Path | None = None) -> Path:
    if not name or Path(name).name != name or name in {".", ".."} or "/" in name or "\\" in name:
        raise ValueError("index-name must be one directory name")
    raw_target = _repo_relative(Path(root)) / name
    if raw_target.is_symlink() or any(parent.is_symlink() for parent in raw_target.parents):
        raise ValueError("legacy index path must not use a symbolic link")
    target = raw_target.resolve()
    configured = Path(os.getenv("VECTOR_STORE_PERSIST_DIR", "./faiss_index"))
    formal_candidates = {_repo_relative(configured).resolve(), configured.resolve(),
                         (SERVICE_ROOT / configured).resolve(), (PROJECT_ROOT / "faiss_index").resolve()}
    if formal_index_dir is not None:
        formal_candidates.add(_repo_relative(formal_index_dir).resolve())
    # Protect both the configured formal directory and the repository default.
    if any(target == item or item in target.parents or target in item.parents for item in formal_candidates):
        raise ValueError("legacy index must not overlap the formal FAISS index directory")
    if target.exists() and not target.is_dir():
        raise ValueError("legacy index path must be a directory")
    return target

#加载并校验 legacy_sources.json 这个“来源映射”文件
def _load_active_mapping(index_dir: Path, manifest: IndexManifest) -> dict[int, dict[str, object]]:
    mapping_name = "legacy_sources.json"
    build_dir = index_dir / f"build-{manifest.index_build_id}"
    try:
        identity = json.loads((build_dir / "index_manifest.json").read_text(encoding="utf-8"))
        if any(identity.get(key) != expected for key, expected in {
            "source": "legacy", "database": LEGACY_DATABASE, "index_name": "doctrail_legacy_reproduce",
            "source_mapping_file": mapping_name, "source_mapping_schema_version": 1,
        }.items()):
            raise ValueError("active build is not a supported legacy index")
        value = json.loads((build_dir / mapping_name).read_text(encoding="utf-8"))
        if (value.get("schema_version") != 1 or value.get("source") != "legacy"
                or value.get("database") != LEGACY_DATABASE
                or value.get("index_build_id") != str(manifest.index_build_id)):
            raise ValueError("source mapping identity does not match the build")
        chunks = value["chunks"]
        mapping = {int(item["chunk_id"]): item for item in chunks}
        if len(mapping) != len(chunks) or len(mapping) != manifest.chunk_count:
            raise ValueError("source mapping count does not match the build")
        return mapping
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise ValueError("active legacy source mapping is invalid") from exc

#把检索结果（VectorMatch 列表）和来源映射拼装成可读的结果字典
#VectorMatch只有向量相关信息，这些业务信息存在 `legacy_sources.json` 的 mapping 字典里。
def _results(matches: list[VectorMatch], mapping: dict[int, dict[str, object]]) -> list[dict[str, object]]:
    results = []
    for match in matches:
        source = mapping[match.point.chunk_id]
        if int(source["doc_id"]) != match.point.doc_id:
            raise ValueError("source mapping doc_id does not match the retrieved point")
        results.append({"score": match.score, "chunk_id": match.point.chunk_id,
                        "doc_id": match.point.doc_id, "chunk_index": match.point.chunk_index,
                        "text": match.point.text, "source_name": source["source_name"],
                        "page": source["page"], "orphan": source["orphan"]})
    return results

#只查询模式
def query_active(args: argparse.Namespace) -> dict[str, object]:
    index_dir = _safe_index_dir(args.index_root, args.index_name, args.formal_index_dir)
    if not (index_dir / "active.pointer").is_file():
        raise ValueError("legacy index has no active build")
    store = FaissVectorStore(index_dir)
    manifest = store.manifest()
    if manifest is None:
        raise ValueError("legacy index has no active build")
    if manifest.embedding_model != args.model or not manifest.normalized:
        raise ValueError("active legacy index model or normalization does not match the query provider")
    mapping = _load_active_mapping(index_dir, manifest)
    provider = HuggingFaceEmbeddingProvider(model_name=args.model, cache_dir=str(_repo_relative(args.cache_dir)), normalized=True)
    vector = provider.embed([args.query])[0]
    if provider.dimension != manifest.dimension:
        raise ValueError("active legacy index dimension does not match embedding provider")
    matches = store.search_build(manifest.index_build_id, vector, args.top_k)
    return {"query": args.query, "top_k": args.top_k, "index_build_id": str(manifest.index_build_id),
            "results": _results(matches, mapping)}

#实际重建流程
def rebuild(args: argparse.Namespace) -> dict[str, object]:
    if args.source != "legacy":
        raise ValueError("only --source legacy is supported")
    if args.index_name != "doctrail_legacy_reproduce":
        raise ValueError("legacy index name must be doctrail_legacy_reproduce")
    if args.database != LEGACY_DATABASE:
        raise ValueError("legacy rebuild requires database agentcraft_legacy")
    if args.top_k <= 0:
        raise ValueError("top-k must be positive")
    if args.query_only:
        if not args.query:
            raise ValueError("--query-only requires --query")
        return query_active(args)
    index_dir = _safe_index_dir(args.index_root, args.index_name, args.formal_index_dir)
    reader = LegacyReader.from_env()
    rows = reader.read_chunks()
    if not rows:
        raise ValueError("legacy knowledge_chunk contains no chunks")

    provider = HuggingFaceEmbeddingProvider(model_name=args.model, cache_dir=str(_repo_relative(args.cache_dir)), normalized=True)
    vectors = provider.embed([row.text for row in rows])
    if provider.dimension is None or len(vectors) != len(rows):
        raise ValueError("embedding provider returned an invalid vector set")
    points = [
        VectorPoint(chunk_id=row.chunk_id, doc_id=row.doc_id,
                    batch_id=uuid5(NAMESPACE_URL, f"agentcraft-legacy/doc/{row.doc_id}"),
                    chunk_index=row.chunk_index, text=row.text, vector=vector)
        for row, vector in zip(rows, vectors, strict=True)
    ]
    digest_payload = "\n".join(f"{p.chunk_id}:{p.doc_id}:{p.batch_id}:{p.chunk_index}:{p.text}" for p in points)
    digest = hashlib.sha256(digest_payload.encode("utf-8")).hexdigest()
    index_store = FaissVectorStore(index_dir)
    build_id = uuid4()
    try:
        manifest = index_store.add_build(build_id, points, embedding_model=provider.model_name,
                                         dimension=provider.dimension or 0, normalized=provider.normalized,
                                         active_chunk_digest=digest)
        orphan_ids = sorted({row.doc_id for row in rows if row.orphan})
        source_mapping = {"schema_version": 1, "source": "legacy", "database": LEGACY_DATABASE,
                          "index_build_id": str(build_id),
                          "chunks": [{"chunk_id": row.chunk_id, "doc_id": row.doc_id,
                                      "source_name": row.source_name, "page": row.page,
                                      "orphan": row.orphan} for row in rows]}
        build_dir = index_dir / f"build-{build_id}"
        manifest_path = build_dir / "index_manifest.json"
        manifest_json = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest_json.update({"source": "legacy", "database": LEGACY_DATABASE,
                              "index_name": args.index_name,
                              "source_mapping_file": "legacy_sources.json",
                              "source_mapping_schema_version": 1})
        manifest_path.write_text(json.dumps(manifest_json, ensure_ascii=False, indent=2), encoding="utf-8")
        (build_dir / "legacy_sources.json").write_text(json.dumps(source_mapping, ensure_ascii=False, indent=2), encoding="utf-8")
        linked_doc_ids = sorted({row.doc_id for row in rows if not row.orphan})
        all_doc_ids = sorted({row.doc_id for row in rows})
        orphan_chunk_count = sum(row.orphan for row in rows)
        report = {"source": "legacy", "database": args.database, "index_name": args.index_name,
                  "index_build_id": str(manifest.index_build_id), "chunk_count": manifest.chunk_count,
                  "knowledge_doc_count": reader.last_stats["knowledge_doc_count"],
                  "distinct_doc_id_count": len(all_doc_ids), "linked_doc_count": len(linked_doc_ids),
                  "orphan_doc_count": len(orphan_ids), "linked_chunk_count": len(rows) - orphan_chunk_count,
                  "orphan_doc_ids": orphan_ids, "orphan_chunk_count": orphan_chunk_count,
                  "orphan_chunk_ratio": orphan_chunk_count / len(rows),
                  "doc_name_policy": "legacy-doc-{doc_id} for orphan chunks",
                  "source_mapping_file": "legacy_sources.json"}
        mapping = _load_active_mapping(index_dir, manifest)
        # Validate the unpublished build even when no human query was supplied.
        if not index_store.search_build(build_id, vectors[0], 1):
            raise ValueError("new legacy build failed retrieval validation")
        if args.query:
            query_vector = provider.embed([args.query])[0]
            report["query"] = args.query
            report["search_results"] = _results(index_store.search_build(build_id, query_vector, args.top_k), mapping)
        (build_dir / "legacy_rebuild_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        index_store.activate(build_id)
        return report
    except Exception:
        try:
            index_store.delete_by_build(build_id)
        except Exception:
            pass
        raise


def main() -> int:
    try:
        print(json.dumps(rebuild(parse_args()), ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"legacy rebuild failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

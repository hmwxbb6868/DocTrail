from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from langchain_huggingface import HuggingFaceEmbeddings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify the local embedding stack.")
    parser.add_argument("--model", default="BAAI/bge-small-zh-v1.5")
    parser.add_argument("--cache-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    embeddings = HuggingFaceEmbeddings(
        model_name=args.model,
        cache_folder=str(args.cache_dir),
        model_kwargs={"device": "cpu"},
        encode_kwargs={"normalize_embeddings": True},
    )
    vector = embeddings.embed_query("DocTrail Python 3.11 compatibility check")
    norm = math.sqrt(sum(value * value for value in vector))
    print(
        json.dumps(
            {
                "model": args.model,
                "dimension": len(vector),
                "normalized": math.isclose(norm, 1.0, rel_tol=1e-5),
                "norm": round(norm, 6),
            },
            ensure_ascii=True,
        )
    )


if __name__ == "__main__":
    main()


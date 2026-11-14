from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import uuid


MODEL_DIRECTORY = "models--BAAI--bge-small-zh-v1.5"
COPIED_DIRECTORIES = ("snapshots", "blobs", "refs")
SAFE_REVISION = re.compile(r"^[A-Za-z0-9._-]+$")


def validate_model_directory(model_directory: Path) -> None:
    for name in COPIED_DIRECTORIES:
        path = model_directory / name
        if not path.is_dir():
            raise RuntimeError(f"model cache is missing required directory: {name}")

    reference_files = [path for path in (model_directory / "refs").rglob("*") if path.is_file()]
    if not reference_files:
        raise RuntimeError("model cache refs directory has no reference files")

    for reference_file in reference_files:
        revision = reference_file.read_text(encoding="utf-8").strip()
        if not revision or not SAFE_REVISION.fullmatch(revision):
            raise RuntimeError(f"model cache ref has an invalid revision: {reference_file.name}")
        if not (model_directory / "snapshots" / revision).is_dir():
            raise RuntimeError(f"model cache ref points to a missing snapshot: {reference_file.name}")


def copy_model_cache(source_root: Path, target_root: Path) -> None:
    source_model = source_root / MODEL_DIRECTORY
    validate_model_directory(source_model)

    target_root.mkdir(parents=True, exist_ok=True)
    staging_model = target_root / f".{MODEL_DIRECTORY}.staging-{uuid.uuid4().hex}"
    target_model = target_root / MODEL_DIRECTORY
    try:
        staging_model.mkdir()
        for name in COPIED_DIRECTORIES:
            shutil.copytree(source_model / name, staging_model / name, symlinks=False)
        validate_model_directory(staging_model)

        if target_model.exists():
            shutil.rmtree(target_model)
        os.replace(staging_model, target_model)
    finally:
        if staging_model.exists():
            shutil.rmtree(staging_model)


def main() -> None:
    parser = argparse.ArgumentParser(description="Initialize the offline BGE model cache volume.")
    parser.add_argument("--source-root", type=Path, default=Path("/seed"))
    parser.add_argument("--target-root", type=Path, default=Path("/data/model-cache"))
    args = parser.parse_args()
    copy_model_cache(args.source_root, args.target_root)
    print(f"model cache initialized: {MODEL_DIRECTORY}")


if __name__ == "__main__":
    main()

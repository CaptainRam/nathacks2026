"""Download pinned, public quantized checkpoints into a task-specific external cache."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time

os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
from huggingface_hub import hf_hub_download


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", default="/tmp/allergy-model-benchmark/models")
    parser.add_argument("--only")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "models.json").read_text())
    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    for model in manifest["models"]:
        if args.only and model["id"] != args.only:
            continue
        directory = cache / model["id"]
        metadata = {"model": model, "artifacts": []}
        for name, size in [(model["file"], model["bytes"])] + (
            [(model["projector"], model["projector_bytes"])] if model.get("projector") else []
        ):
            print(f"Downloading {model['id']}: {name} ({size / 1e9:.3f} GB)", flush=True)
            start = time.monotonic()
            path = Path(hf_hub_download(repo_id=model["repo"], filename=name,
                revision=model["revision"], local_dir=directory, token=False))
            if path.stat().st_size != size:
                raise ValueError(f"Incorrect size: {path}")
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
                    digest.update(block)
            metadata["artifacts"].append({"file": name, "bytes": size, "sha256": digest.hexdigest()})
            print(f"Ready {name}; elapsed={time.monotonic()-start:.1f}s", flush=True)
        (directory / "ready.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(f"MODEL_READY {model['id']}", flush=True)


if __name__ == "__main__":
    main()

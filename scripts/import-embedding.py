#!/usr/bin/env python3
"""Download the official, digest-pinned Ollama GGUF and import it locally.

This route supports networks whose DNS proxy is rejected by Ollama's remote-pull
SSRF protection; it does not change or disable that protection.
"""
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / ".data" / "ollama" / "import"
DEST.mkdir(parents=True, exist_ok=True)
BASE = "https://registry.ollama.ai/v2/library/bge-m3"


def main():
    request = urllib.request.Request(BASE + "/manifests/latest", headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        manifest = json.load(response)
    layer = next(item for item in manifest["layers"] if item["mediaType"] == "application/vnd.ollama.image.model")
    digest = layer["digest"]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("Invalid model digest")
    target = DEST / "bge-m3.gguf"
    expected = digest.split(":", 1)[1]
    if target.exists():
        with target.open("rb") as source:
            valid = hashlib.file_digest(source, "sha256").hexdigest() == expected
        if not valid:
            target.unlink()
    if not target.exists():
        temporary = target.with_suffix(".part")
        print(f"Downloading official BGE-M3 model ({layer['size'] // 1_000_000} MB), SHA-256 {expected}", flush=True)
        checksum = hashlib.sha256()
        downloaded = 0
        last_progress = -1
        with urllib.request.urlopen(BASE + "/blobs/" + digest, timeout=120) as response, temporary.open("wb") as output:
            while block := response.read(4 * 1024 * 1024):
                output.write(block)
                checksum.update(block)
                downloaded += len(block)
                progress = int(downloaded * 100 / layer["size"])
                if progress // 10 > last_progress:
                    last_progress = progress // 10
                    print(f"Model download: {progress}%", flush=True)
        if downloaded != layer["size"] or checksum.hexdigest() != expected:
            temporary.unlink(missing_ok=True)
            raise ValueError("Model digest or size mismatch")
        temporary.replace(target)
    (DEST / "source.json").write_text(json.dumps({"source": BASE, "modelDigest": digest, "manifest": manifest}, indent=2))
    modelfile = DEST / "Modelfile"
    modelfile.write_text("FROM ./bge-m3.gguf\nPARAMETER num_ctx 8192\n")
    print("Official model digest verified; importing through ollama create", flush=True)
    subprocess.run([str(ROOT / ".data/tools/ollama/ollama"), "create", "bge-m3", "-f", str(modelfile)], check=True)


if __name__ == "__main__":
    main()

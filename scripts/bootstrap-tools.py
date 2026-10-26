#!/usr/bin/env python3
"""Install project-local Maven / Ollama binaries without changing the system."""
import concurrent.futures
import hashlib
import pathlib
import shutil
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".data" / "tools"
TOOLS.mkdir(parents=True, exist_ok=True)


def download(url, destination):
    if destination.exists():
        return destination
    temporary = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "travelbao-setup/0.1"})
    print(f"Downloading {destination.name}", flush=True)
    try:
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    print(f"Downloaded {destination.name}: {destination.stat().st_size} bytes", flush=True)
    return destination


def maven():
    name = "apache-maven-3.9.11-bin.tar.gz"
    base = "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/"
    archive = download(base + name, TOOLS / name)
    checksum_file = download(base + name + ".sha512", TOOLS / (name + ".sha512"))
    expected = checksum_file.read_text().split()[0]
    actual = hashlib.sha512(archive.read_bytes()).hexdigest()
    if actual.lower() != expected.lower():
        archive.unlink()
        raise RuntimeError("Maven archive SHA-512 mismatch")
    target = TOOLS / "apache-maven-3.9.11"
    if not target.exists():
        with tarfile.open(archive) as source:
            source.extractall(TOOLS, filter="data")
    print("Maven 3.9.11 ready (Apache SHA-512 verified)", flush=True)


def ollama():
    target = TOOLS / "ollama"
    target.mkdir(exist_ok=True)
    archive = download(
        "https://github.com/ollama/ollama/releases/download/v0.40.8/ollama-darwin.tgz",
        TOOLS / "ollama-0.40.8-darwin.tgz",
    )
    if not (target / "ollama").exists():
        with tarfile.open(archive) as source:
            source.extractall(target, filter="data")
    print("Ollama 0.40.8 extracted in project-local tools directory", flush=True)


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        tasks = [executor.submit(maven), executor.submit(ollama)]
        for task in tasks:
            task.result()

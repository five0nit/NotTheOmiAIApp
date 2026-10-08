#!/usr/bin/env python3
"""Prepare hash-pinned public dependencies; never accesses private audio or credentials.

Python 3.10+. Downloads are optional: --offline --cache-from PATH accepts a
read-only cache containing whisper-source-6e4ab85.tar.gz and whisper-models/.
No candidate scripts, package installers, or source CMake files are executed here.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache"
COMMIT = "6e4ab854f67f743900934a703d5603419384c961"
SOURCE_SHA = "55b585aec5e1bc03e2a5cea61da97c6406fa833996cf7daeed704972273cc039"
SOURCE_URL = f"https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/{COMMIT}"
ARCHIVE_NAME = "whisper-source-6e4ab85.tar.gz"
SOURCE = CACHE / "whisper.cpp-6e4ab85"
REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
MODEL = "ggml-small.en-q5_1.bin"
MODEL_SHA = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"
MODEL_BYTES = 190098681
MODEL_URL = f"https://huggingface.co/ggerganov/whisper.cpp/resolve/{REVISION}/{MODEL}"
MODEL_LICENSE_SHA = "b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d"
MODEL_LICENSE = '''MIT License

Copyright (c) 2022 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
'''


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_hash(path, expected):
    if not path.is_file() or path.is_symlink() or sha(path) != expected:
        raise ValueError(f"Missing or mismatched pinned dependency: {path.name}")


def fetch(url, dest, expected, candidates=(), offline=False):
    """Use only verified bytes; failed downloads never replace cached inputs."""
    if dest.is_file() and not dest.is_symlink() and sha(dest) == expected:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    for source in candidates:
        if source.is_file() and not source.is_symlink() and sha(source) == expected:
            with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as out:
                temp = Path(out.name)
                try:
                    with source.open("rb") as incoming:
                        shutil.copyfileobj(incoming, out)
                    out.close()
                    require_hash(temp, expected)
                    temp.replace(dest)
                    return
                finally:
                    temp.unlink(missing_ok=True)
    if offline:
        raise ValueError(f"Verified offline dependency unavailable: {dest.name}")
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as out:
        temp = Path(out.name)
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                shutil.copyfileobj(response, out)
            out.close()
            require_hash(temp, expected)
            temp.replace(dest)
        finally:
            temp.unlink(missing_ok=True)


def verify_source(archive, source=SOURCE, repair_missing=True):
    """Compare *all* files against the pinned tar, reject extras and symlinks.

    Missing files may be safely materialized; modified files are never repaired
    silently. No tar extraction helper is used; archive links are forbidden.
    """
    require_hash(archive, SOURCE_SHA)
    if source.is_symlink() or any(p.is_symlink() for p in source.parents):
        raise ValueError("Symlink in source root")
    expected_paths = set()
    prefix = f"whisper.cpp-{COMMIT}/"
    with tarfile.open(archive) as tar:
        for member in tar:
            if member.isdir():
                continue
            if not member.isfile() or not member.name.startswith(prefix):
                raise ValueError("Unexpected pinned archive member")
            rel = PurePosixPath(member.name[len(prefix):])
            if not rel.parts or rel.is_absolute() or ".." in rel.parts:
                raise ValueError("Unsafe source archive path")
            if rel.as_posix() in expected_paths:
                raise ValueError("Duplicate pinned source member")
            expected_paths.add(rel.as_posix())
            target = source / str(rel)
            if target.is_symlink() or any(p.is_symlink() for p in target.parents):
                raise ValueError("Symlink in pinned source tree")
            stream = tar.extractfile(member)
            if stream is None:
                raise ValueError("Missing pinned archive content")
            with stream:
                expected = stream.read()
            if not target.exists() and repair_missing:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(expected)
            if not target.is_file() or target.read_bytes() != expected:
                raise ValueError(f"Pinned source differs: {rel}")
    actual_paths = set()
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError("Symlink in pinned source tree")
        if path.is_file():
            actual_paths.add(path.relative_to(source).as_posix())
    if actual_paths != expected_paths:
        raise ValueError("Unexpected files in pinned source tree")
    return len(expected_paths)


def prepare(offline=False, cache_from=()):
    archive = CACHE / ARCHIVE_NAME
    fetch(SOURCE_URL, archive, SOURCE_SHA,
          [p / ARCHIVE_NAME for p in cache_from], offline)
    count = verify_source(archive)
    model = CACHE / "whisper-models" / MODEL
    fetch(MODEL_URL, model, MODEL_SHA,
          [p / "whisper-models" / MODEL for p in cache_from], offline)
    if model.stat().st_size != MODEL_BYTES:
        raise ValueError("Pinned model size mismatch")
    return {"source_commit": COMMIT, "source_url": SOURCE_URL,
            "source_archive_sha256": sha(archive), "source_files_verified": count,
            "model_revision": REVISION, "model_url": MODEL_URL,
            "model_sha256": sha(model), "model_bytes": model.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--cache-from", type=Path, action="append", default=[],
                        help="Read-only dependency cache, repeatable")
    args = parser.parse_args()
    print(json.dumps(prepare(args.offline, args.cache_from), indent=2))


if __name__ == "__main__":
    main()

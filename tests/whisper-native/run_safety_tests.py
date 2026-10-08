#!/usr/bin/env python3
"""Run actual JNI safety checks against an explicitly fake backend, plus ASan/UBSan helpers.

This is not model/inference accuracy evidence. No microphone, device, network,
Gradle, signing key or private audio is used. All products use a temporary tree.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
CPP = ROOT / "app/src/main/cpp"
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_whisper import SOURCE, CACHE, ARCHIVE_NAME, verify_source, require_hash, MODEL_SHA


def run(args, **kwargs):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, text=True,
                            capture_output=True, **kwargs)
    if result.returncode:
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        raise subprocess.CalledProcessError(result.returncode, args)
    if result.stderr:
        raise AssertionError(f"Unexpected stderr from {args[0]}: {result.stderr}")
    return result.stdout.strip()


def must_reject(action):
    try:
        action()
    except ValueError:
        return
    raise AssertionError("Corrupted/unpinned input was accepted")


def main():
    verified = verify_source(CACHE / ARCHIVE_NAME, repair_missing=False)
    java_home = Path(shutil.which("javac")).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="whisper-native-safety-") as tmp:
        temp = Path(tmp)
        run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O1", "-g",
             "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-I", CPP,
             TESTS / "safety_test.cpp", "-o", temp / "safety-test"])
        helpers = run([temp / "safety-test"], timeout=30)
        run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC", "-pthread",
             "-I", java_home / "include", "-I", java_home / "include/linux",
             "-I", SOURCE / "include", "-I", SOURCE / "ggml/include",
             CPP / "whisper_jni.cpp", TESTS / "fake_whisper.cpp",
             "-o", temp / "libnottheomi-whisper.so"])
        run(["javac", "-encoding", "UTF-8", "-d", temp,
             ROOT / "app/src/main/java/app/nottheomi/ai/WhisperNative.java",
             TESTS / "NativeSafetyTest.java"])
        jni = run(["java", "-Xcheck:jni", f"-Djava.library.path={temp}", "-cp", temp,
                   "NativeSafetyTest"], timeout=30)
        if not re.fullmatch(r"JNI mock-backend safety checks passed: \d+", jni):
            raise AssertionError("Unexpected native logging")
        copied = temp / "source"
        shutil.copytree(SOURCE, copied)
        extra = copied / "unexpected.cmake"
        extra.write_text("unverified")
        must_reject(lambda: verify_source(CACHE / ARCHIVE_NAME, copied, False))
        extra.unlink()
        target = copied / "CMakeLists.txt"
        original = target.read_bytes()
        target.write_bytes(original + b"\n# tampered\n")
        must_reject(lambda: verify_source(CACHE / ARCHIVE_NAME, copied, False))
        target.unlink()
        target.symlink_to(SOURCE / "CMakeLists.txt")
        must_reject(lambda: verify_source(CACHE / ARCHIVE_NAME, copied, False))
        damaged = temp / "bad-model.bin"
        damaged.write_bytes(b"not the pinned model")
        must_reject(lambda: require_hash(damaged, MODEL_SHA))
        summary = {"scope": "host API safety; fake inference backend, not recognition accuracy",
                   "source_files_verified": verified,
                   "sanitizers": "ASan+UBSan", "cpp_helpers": helpers,
                   "jni": jni, "jni_check": "-Xcheck:jni, no stderr",
                   "provenance_negative_controls": 4, "passed": True}
    summary["temporary_outputs_removed"] = not temp.exists()
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()

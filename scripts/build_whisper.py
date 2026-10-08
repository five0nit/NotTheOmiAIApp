#!/usr/bin/env python3
"""Build pinned CPU-only Android Whisper; no Gradle, ADB, signing, or audio access.

Run prepare_whisper.py first. Builds never download anything. Default builds
both supported ABIs; --abi can be repeated. --verify-reproducible performs an
independent clean rebuild and requires byte-identical stripped JNI artifacts.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from prepare_whisper import (ROOT, CACHE, SOURCE, COMMIT, SOURCE_SHA, SOURCE_URL,
                             ARCHIVE_NAME, MODEL, MODEL_SHA, MODEL_BYTES, MODEL_URL,
                             REVISION, MODEL_LICENSE, MODEL_LICENSE_SHA,
                             require_hash, sha, verify_source)

NDK_VERSION = "27.2.12479018"
ABIS = ("arm64-v8a", "x86_64")
LIBRARY = "libnottheomi-whisper.so"
CPP = ROOT / "app/src/main/cpp"
RECEIPT = ROOT / "verification/whisper-native-build.json"


def run(command, env=None, log=None):
    result = subprocess.run([str(x) for x in command], env=env, cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if log:
        log.write_text(result.stdout)
    if result.returncode:
        print(result.stdout)
        raise subprocess.CalledProcessError(result.returncode, command)
    return result.stdout


def native_inputs():
    paths = sorted(p for p in CPP.rglob("*") if p.is_file())
    paths += [ROOT / "scripts/prepare_whisper.py", ROOT / "scripts/build_whisper.py",
              ROOT / "app/src/main/java/app/nottheomi/ai/WhisperNative.java"]
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}


def audit_elf(library, abi, tools):
    header = run([tools / "llvm-readelf", "-h", library])
    expected = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
    if expected not in header:
        raise ValueError("Wrong ELF machine")
    dynamic = run([tools / "llvm-readelf", "-d", library])
    needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    if not set(needed) <= {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}:
        raise ValueError(f"Unexpected runtime dependency: {needed}")
    symbols = run([tools / "llvm-nm", "--dynamic", "--defined-only", library])
    names = {line.split()[-1] for line in symbols.splitlines() if line.strip()}
    exports = {f"Java_app_nottheomi_ai_WhisperNative_{method}"
               for method in ("openFile", "transcribe", "cancel", "close")}
    if names != exports:
        raise ValueError(f"Unexpected exported symbols: {names}")
    imports = run([tools / "llvm-nm", "--dynamic", "--undefined-only", library])
    imported = {line.split()[-1].split("@")[0] for line in imports.splitlines() if line.strip()}
    forbidden = {"socket", "connect", "send", "recv", "getaddrinfo", "curl_easy_init"}
    if imported & forbidden:
        raise ValueError("Network symbols linked into CPU-only library")
    phdr = run([tools / "llvm-readelf", "--program-headers", "--wide", library])
    load = [line for line in phdr.splitlines() if line.strip().startswith("LOAD")]
    if not load or any(int(line.split()[-1], 16) < 16384 for line in load):
        raise ValueError("JNI does not support 16KiB ELF alignment")
    if "TEXTREL" in dynamic or re.search(r"GNU_STACK.*RWE", phdr):
        raise ValueError("Unsafe ELF relocation/stack flags")
    return {"machine": expected, "needed": sorted(needed), "exports": sorted(names),
            "network_imports": [], "load_alignment_min": 16384,
            "sha256": sha(library), "bytes": library.stat().st_size}


def build_one(abi, directory, ndk, jobs, env):
    directory.mkdir(parents=True, exist_ok=True)
    command = ["cmake", "-S", str(CPP), "-B", str(directory), "-G", "Unix Makefiles",
               f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
               f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26",
               "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
               "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0",
               "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -g0", f"-DWHISPER_SOURCE={SOURCE}"]
    run(command, env, directory / "configure.log")
    compile_command = ["cmake", "--build", str(directory), "--target", "nottheomi-whisper",
                       "--parallel", str(jobs)]
    run(compile_command, env, directory / "build.log")
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    library = directory / LIBRARY
    if not library.is_file():
        raise ValueError("Expected JNI output was not built")
    run([tools / "llvm-strip", "--strip-unneeded", library])
    entries = json.loads((directory / "compile_commands.json").read_text())
    commands = "\n".join(entry["command"] for entry in entries)
    prohibited = ("-march=native", "-mcpu=native", "+dotprod", "+fp16", "-mavx", "-mfma", "-mf16c", "-mbmi")
    if any(flag in commands for flag in prohibited):
        raise ValueError("Unsafe distributed CPU flags")
    if abi == "arm64-v8a" and "-march=armv8-a" not in commands:
        raise ValueError("Portable ARM baseline missing")
    summary = audit_elf(library, abi, tools)
    summary.update({"cpu_baseline": "armv8-a (NEON), no optional dotprod/fp16/i8mm" if abi == "arm64-v8a"
                    else "NDK x86_64 baseline, no optional AVX/FMA/F16C/BMI",
                    "configure_command": command, "build_command": compile_command,
                    "compile_commands_sha256": sha(directory / "compile_commands.json")})
    return library, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, action="append")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--verify-reproducible", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 32:
        parser.error("--jobs must be 1..32")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME",
                    str(Path.home() / ".openclaw/workspace/.android-sdk"))))
    ndk = (args.ndk or sdk / "ndk" / NDK_VERSION).resolve()
    properties = ndk / "source.properties"
    if not properties.is_file() or not re.search(r"Pkg.Revision\s*=\s*27\.2\.12479018\s*$", properties.read_text(), re.M):
        raise SystemExit("Install/select pinned Android NDK 27.2.12479018")
    count = verify_source(CACHE / ARCHIVE_NAME, repair_missing=False)
    model = CACHE / "whisper-models" / MODEL
    require_hash(model, MODEL_SHA)
    if model.stat().st_size != MODEL_BYTES:
        raise ValueError("Pinned model size mismatch")
    if hashlib.sha256(MODEL_LICENSE.encode()).hexdigest() != MODEL_LICENSE_SHA:
        raise ValueError("Model license text checksum mismatch")
    inputs = native_inputs()
    env = os.environ.copy()
    # Prevent upstream git detection from capturing the *app's* mutable commit.
    env.update({"SOURCE_DATE_EPOCH": "0", "TZ": "UTC", "LC_ALL": "C",
                "GIT_CEILING_DIRECTORIES": str(SOURCE.parent)})
    # Ambient compiler flags are not part of the reproducible recipe.
    for variable in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CC", "CXX"):
        env.pop(variable, None)
    abis = list(dict.fromkeys(args.abi or ABIS))
    results, artifacts = {}, {}
    for abi in abis:
        print(f"Building CPU-only {abi}", flush=True)
        library, result = build_one(abi, CACHE / "whisper-android-build" / abi, ndk, args.jobs, env)
        if args.verify_reproducible:
            with tempfile.TemporaryDirectory(prefix="whisper-rebuild-", dir=CACHE) as temporary:
                rebuilt, _ = build_one(abi, Path(temporary), ndk, args.jobs, env)
                if sha(rebuilt) != sha(library):
                    raise ValueError(f"Independent rebuild differs for {abi}")
            result["independent_rebuild_byte_identical"] = True
        else:
            result["independent_rebuild_byte_identical"] = None
        results[abi], artifacts[abi] = result, library
        print(json.dumps({"abi": abi, "sha256": result["sha256"], "bytes": result["bytes"],
                          "reproducible": result["independent_rebuild_byte_identical"]}), flush=True)
    if native_inputs() != inputs:
        raise ValueError("Native inputs changed during compilation; retry a stable build")
    # Publish only verified outputs. No legacy ABI or unrelated asset is touched.
    for abi, library in artifacts.items():
        dest = ROOT / "app/src/main/jniLibs" / abi / LIBRARY
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(library, dest)
        require_hash(dest, results[abi]["sha256"])
        results[abi]["path"] = dest.relative_to(ROOT).as_posix()
    assets = ROOT / "app/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    if not (assets / MODEL).is_file() or sha(assets / MODEL) != MODEL_SHA:
        shutil.copyfile(model, assets / MODEL)
    require_hash(assets / MODEL, MODEL_SHA)
    licenses = assets / "licenses"
    licenses.mkdir(exist_ok=True)
    shutil.copyfile(SOURCE / "LICENSE", licenses / "whisper.cpp-MIT.txt")
    (licenses / "whisper-model-MIT.txt").write_text(MODEL_LICENSE)
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    receipt = {"schema_version": 1, "source_commit": COMMIT, "source_url": SOURCE_URL,
               "source_archive_sha256": SOURCE_SHA, "source_files_verified": count,
               "model": MODEL, "model_revision": REVISION, "model_url": MODEL_URL,
               "model_sha256": sha(assets / MODEL), "model_bytes": MODEL_BYTES,
               "licenses": {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(licenses.glob("whisper*"))},
               "ndk": NDK_VERSION, "android_api": 26, "cxx_runtime": "c++_static",
               "cmake_version": run(["cmake", "--version"]).splitlines()[0],
               "clang_version": run([tools / "clang", "--version"]).splitlines()[0],
               "clang_sha256": sha(tools / "clang"), "cpu_only": True,
               "network_backend": False, "dynamic_backends": False,
               "native_input_sha256": inputs, "abis": results,
               "runtime_inference_verified": False,
               "verification_boundary": "Cross-compiled and ELF-audited; device/JNI inference is a separate gate."}
    RECEIPT.parent.mkdir(exist_ok=True)
    RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Verified receipt: {RECEIPT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Production Vosk -> RefinementEngine/Whisper public-fixture shell benchmark.

No app install, settings, capture, library access or logcat. Uses the caller's
existing ADB server/serial only. --build-only compiles and verifies both ABIs
without invoking ADB. Device receipts are written only after checked cleanup.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import stat
import struct
import subprocess
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ABIS = ('arm64-v8a', 'x86_64')
PINS = {
    'app/src/main/assets/model.zip': '30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498',
    'app/src/main/assets/ggml-small.en-q5_1.bin': 'bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30',
    'app/src/androidTest/assets/jfk.wav': '59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e',
}
AARS = {
    'vosk': ('com.alphacephei/vosk-android/0.3.75', 'vosk-android-0.3.75.aar',
             'ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad', 'libvosk.so'),
    'jna': ('net.java.dev.jna/jna/5.18.1', 'jna-5.18.1.aar',
            '7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0', 'libjnidispatch.so'),
}
MODEL_ROOT = 'vosk-model-small-en-us-0.15'
MAX_EXTRACT = 100 * 1024 * 1024


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(command, timeout=120):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # No app data or logcat is queried; only bounded command diagnostics.
        raise RuntimeError(f'Command failed ({result.returncode}): {result.stdout[-2000:]} {result.stderr[:2000]} {result.stderr[-2000:]}')
    return result.stdout.strip()


def verify(path, expected):
    if not re.fullmatch(r'[0-9a-f]{64}', expected) or digest(path) != expected:
        raise RuntimeError(f'Checksum mismatch: {path}')


def checked_members(archive):
    entries = archive.infolist()
    if len(entries) > 256 or sum(i.file_size for i in entries) > MAX_EXTRACT:
        raise RuntimeError('Archive extraction budget exceeded')
    seen = set()
    for entry in entries:
        name = entry.filename
        parts = name.rstrip('/').split('/')
        mode = entry.external_attr >> 16
        if (not name or name.startswith('/') or '\\' in name or '\x00' in name
                or any(part in ('', '.', '..') for part in parts) or name in seen
                or stat.S_ISLNK(mode) or entry.flag_bits & 1
                or entry.file_size > 64 * 1024 * 1024):
            raise RuntimeError('Unsafe archive entry')
        seen.add(name)
    return entries


def extract_member(archive, entry, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with archive.open(entry) as src, destination.open('xb') as out:
        while True:
            block = src.read(64 * 1024)
            if not block:
                break
            count += len(block)
            if count > entry.file_size or count > 64 * 1024 * 1024:
                raise RuntimeError('Expanded member exceeds budget')
            out.write(block)
    if count != entry.file_size:
        raise RuntimeError('Truncated archive member')


def check_elf(path, abi):
    with path.open('rb') as stream:
        header = stream.read(20)
    machine = {'arm64-v8a': 183, 'x86_64': 62}[abi]
    if (len(header) != 20 or header[:6] != b'\x7fELF\x02\x01'
            or struct.unpack_from('<H', header, 18)[0] != machine):
        raise RuntimeError(f'Wrong native ABI: {path}')


def prepare(work):
    """Verify fixed inputs, snapshot Java, extract bounded dependencies, compile DEX."""
    inputs = {ROOT / rel: sha for rel, sha in PINS.items()}
    for path, sha in inputs.items():
        verify(path, sha)
    receipt_path = ROOT / 'verification/whisper-native-build.json'
    native_receipt = json.loads(receipt_path.read_text())
    inputs[receipt_path] = digest(receipt_path)
    for rel, sha in native_receipt['native_input_sha256'].items():
        path = ROOT / rel
        if not path.resolve().is_relative_to(ROOT):
            raise RuntimeError('Native receipt path outside repository')
        verify(path, sha)
        inputs[path] = sha
    payload = work / 'payload'
    payload.mkdir()
    # Only this pinned public model is extracted; never accept an arbitrary ZIP.
    with zipfile.ZipFile(ROOT / 'app/src/main/assets/model.zip') as archive:
        entries = checked_members(archive)
        for entry in entries:
            if not entry.filename.startswith(MODEL_ROOT + '/'):
                raise RuntimeError('Unexpected model root')
            if not entry.is_dir():
                extract_member(archive, entry, payload / entry.filename)
    if not (payload / MODEL_ROOT / 'am/final.mdl').is_file():
        raise RuntimeError('Incomplete preview model')
    jars = []
    aar_hashes = {}
    native_hashes = {}
    for name, (coordinate, filename, sha, library) in AARS.items():
        candidates = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1' / coordinate).glob('*/' + filename))
        if not candidates:
            raise RuntimeError(f'Pinned AAR absent from Gradle cache: {coordinate}')
        aar = candidates[0]
        verify(aar, sha)
        inputs[aar] = sha
        aar_hashes[name] = sha
        with zipfile.ZipFile(aar) as archive:
            checked_members(archive)
            jar = work / (name + '-classes.jar')
            extract_member(archive, archive.getinfo('classes.jar'), jar)
            jars.append(jar)
            for abi in ABIS:
                target = payload / 'native' / abi / library
                extract_member(archive, archive.getinfo(f'jni/{abi}/{library}'), target)
                check_elf(target, abi)
                native_hashes[f'{abi}/{library}'] = digest(target)
    for abi in ABIS:
        rel = f'app/src/main/jniLibs/{abi}/libnottheomi-whisper.so'
        source = ROOT / rel
        expected = native_receipt['abis'][abi]['sha256']
        verify(source, expected)
        check_elf(source, abi)
        inputs[source] = expected
        target = payload / 'native' / abi / source.name
        shutil.copyfile(source, target)
        native_hashes[f'{abi}/{source.name}'] = digest(target)
    source_root = ROOT / 'app/src/main/java/app/nottheomi/ai'
    sources = [source_root / (name + '.java') for name in
               ('PreviewModel', 'PreviewRecognizer', 'WhisperModel', 'WhisperNative', 'RefinementEngine')]
    sources.append(ROOT / 'tests/hybrid/HybridDeviceProbe.java')
    inputs[Path(__file__).resolve()] = digest(Path(__file__))
    snapshots = []
    (work / 'sources').mkdir()
    for source in sources:
        inputs[source] = digest(source)
        snapshot = work / 'sources' / source.name
        shutil.copyfile(source, snapshot)
        verify(snapshot, inputs[source])
        snapshots.append(snapshot)
    sdk_value = next(line.split('=', 1)[1] for line in (ROOT / 'local.properties').read_text().splitlines()
                     if line.startswith('sdk.dir='))
    sdk = Path(sdk_value)
    android = sdk / 'platforms/android-34/android.jar'
    # D8 34.0.0 crashes internally on JNA 5.18.1 callback classes; use installed 35.
    tools = sdk / 'build-tools/35.0.0'
    classes, dex = work / 'classes', work / 'dex'
    classes.mkdir()
    dex.mkdir()
    run(['javac', '--release', '17', '-cp', os.pathsep.join(map(str, [android, *jars])),
         '-d', classes, *snapshots])
    run([tools / 'd8', '--lib', android, '--min-api', '26', '--output', dex,
         *sorted(classes.rglob('*.class')), *jars])
    if sorted(path.name for path in dex.iterdir()) != ['classes.dex']:
        raise RuntimeError('Expected a single probe DEX')
    with zipfile.ZipFile(payload / 'probe.jar', 'w') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    for rel in ('app/src/main/assets/ggml-small.en-q5_1.bin', 'app/src/androidTest/assets/jfk.wav'):
        shutil.copyfile(ROOT / rel, payload / Path(rel).name)
        verify(payload / Path(rel).name, PINS[rel])
    recheck(inputs)
    proof = {
        'schema_version': 1,
        'artifact_sha256': {str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path): sha
                            for path, sha in inputs.items()},
        'aar_sha256': aar_hashes,
        'native_sha256_both_abis': native_hashes,
        'classes_jar_sha256': {path.name: digest(path) for path in jars},
        'probe_jar_sha256': digest(payload / 'probe.jar'),
        'source_boundary': 'Exact production Java snapshots; native source/build receipt checked; pinned public audio/models/AARs',
    }
    return payload, inputs, proof


def recheck(inputs):
    for path, sha in inputs.items():
        verify(path, sha)


def assert_idle(shell):
    services = shell('dumpsys', 'activity', 'services', 'app.nottheomi.ai')
    if not services or 'ACTIVITY MANAGER SERVICES' not in services:
        raise RuntimeError('Cannot establish app service state; no benchmark started')
    if any(name in services for name in ('CaptureService', 'RefinementJobService')):
        raise RuntimeError('Active CaptureService or RefinementJobService: postpone CPU benchmark')


def device_run(args, payload, inputs, proof):
    adb = [args.adb, '-P', args.server, '-s', args.serial]

    def shell(*command, timeout=120):
        # One escaped remote command preserves argument boundaries with both adb and adb.exe.
        return run(adb + ['shell', shlex.join(map(str, command))], timeout)

    properties = {key: shell('getprop', key) for key in
                  ('ro.product.model', 'ro.product.cpu.abi', 'ro.build.version.release', 'ro.kernel.qemu')}
    if properties['ro.product.cpu.abi'] != args.abi:
        raise RuntimeError('Device ABI differs from requested artifact')
    assert_idle(shell)
    proof['properties'] = properties
    proof['requested_abi'] = args.abi
    remote = '/data/local/tmp/nottheomi-hybrid-probe-' + uuid.uuid4().hex
    windows = args.adb.lower().endswith('.exe')
    # Borrow the Windows transfer pattern, but discover TEMP rather than assume a username.
    transfer_root = None
    if windows:
        win_temp = run(['cmd.exe', '/d', '/c', 'echo', '%TEMP%']).splitlines()[-1].strip()
        transfer_root = run(['wslpath', '-u', win_temp])
        if not Path(transfer_root).is_dir():
            raise RuntimeError('Windows TEMP is unavailable')
    selected = [path for path in sorted(payload.rglob('*')) if path.is_file()
                and ('native' not in path.relative_to(payload).parts
                     or path.relative_to(payload).parts[1] == args.abi)]
    # Flatten only the selected native ABI beside the JAR for app_process/JNA.
    targets = {path: path.name if 'native' in path.relative_to(payload).parts
               else path.relative_to(payload).as_posix() for path in selected}
    sent = {targets[path]: digest(path) for path in selected}
    proof['device_payload_sha256'] = sent
    created = False
    with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-transfer-', dir=transfer_root) as temp:
        transfer = Path(temp)
        try:
            # Set before mkdir so an interrupted mkdir still takes the owned cleanup path.
            created = True
            shell('mkdir', '-m', '700', remote)
            for directory in sorted({str(PurePosixPath(rel).parent) for rel in targets.values()} - {'.'}):
                shell('mkdir', '-p', remote + '/' + directory)
            for path, rel in targets.items():
                local = transfer / rel
                local.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, local)
                verify(local, sent[rel])
                source = run(['wslpath', '-w', local]) if windows else str(local)
                run(adb + ['push', source, remote + '/' + rel], timeout=180)
                if shell('sha256sum', remote + '/' + rel).split()[0] != sent[rel]:
                    raise RuntimeError('Device payload checksum mismatch')
            recheck(inputs)
            assert_idle(shell)
            command = ['env', 'CLASSPATH=' + remote + '/probe.jar', 'LD_LIBRARY_PATH=' + remote,
                       '/system/bin/app_process64', '-Djava.library.path=' + remote,
                       '-Djna.boot.library.path=' + remote, '-Djna.library.path=' + remote,
                       '-Djna.nounpack=true', '/system/bin', 'app.nottheomi.ai.HybridDeviceProbe',
                       remote + '/' + MODEL_ROOT, remote + '/ggml-small.en-q5_1.bin', remote + '/jfk.wav']
            # Record the exact shell PID before exec; only that owned process may be killed on timeout.
            launch = 'printf "%s\\n" "$$" > ' + shlex.quote(remote + '/probe.pid') + '; exec ' + shlex.join(command)
            began = time.monotonic()
            output = shell('sh', '-c', launch, timeout=args.timeout)
            candidates = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
            if len(candidates) != 1 or candidates[0].get('result') != 'PASS_HYBRID_DEVICE_PUBLIC_FIXTURE':
                raise RuntimeError('Missing unique hybrid device inference pass')
            result = candidates[0]
            if (not result.get('refinement_completed') or not result.get('vosk_closed_before_whisper')
                    or result.get('refinement_committed_bytes') != result.get('audio_bytes')
                    or not 0 < result.get('audio_seconds', 0) <= 11
                    or not 0 < result.get('vosk_first_partial_source_seconds', 0) < 8
                    or result.get('vosk_nonempty_partial_updates', 0) < 1):
                raise RuntimeError('Incomplete hybrid device assertions')
            assert_idle(shell)
            proof.update(result)
            proof['probe_wall_seconds'] = time.monotonic() - began
            # Check source stability and actual remote bytes again, not just adb push status.
            recheck(inputs)
            for rel, sha in sent.items():
                if shell('sha256sum', remote + '/' + rel).split()[0] != sha:
                    raise RuntimeError('Device payload changed during benchmark')
        finally:
            if created:
                # A host adb timeout can leave app_process alive. Guard PID reuse with our unique
                # directory in cmdline before terminating; never pkill or touch app processes.
                cleanup = ('if [ -f ' + shlex.quote(remote + '/probe.pid') + ' ]; then '
                           'read pid < ' + shlex.quote(remote + '/probe.pid') + '; '
                           'case "$pid" in ""|*[!0-9]*) exit 91;; esac; '
                           'if [ -r /proc/"$pid"/cmdline ]; then '
                           'case "$(tr "\\000" " " < /proc/"$pid"/cmdline)" in '
                           '*' + remote + '*) kill -KILL "$pid" 2>/dev/null || true;; '
                           'esac; fi; fi')
                shell('sh', '-c', cleanup)
                shell('rm', '-rf', remote)
                shell('test', '!', '-e', remote)
                proof['remote_cleanup_verified'] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', help='Exact adb or adb.exe; never starts/reconfigures a server explicitly')
    parser.add_argument('--server', help='Existing ADB server port (adb -P)')
    parser.add_argument('--serial', help='Exact selected device serial')
    parser.add_argument('--abi', choices=ABIS)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=600, help='Device inference timeout seconds')
    parser.add_argument('--build-only', action='store_true', help='No ADB: verify inputs and compile both-ABI payload')
    args = parser.parse_args()
    if not args.build_only and not all((args.adb, args.server, args.serial, args.abi)):
        parser.error('--adb, --server, --serial and --abi are required unless --build-only')
    if args.timeout < 30 or args.timeout > 1800:
        parser.error('--timeout must be 30..1800 seconds')
    if args.server and (not args.server.isdigit() or not 1 <= int(args.server) <= 65535):
        parser.error('--server must be a valid port number')
    if args.receipt.exists():
        parser.error('Receipt already exists; use a fresh path so previous evidence is preserved')
    with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-build-') as temp:
        payload, inputs, proof = prepare(Path(temp))
        if args.build_only:
            proof['result'] = 'PASS_HYBRID_PROBE_BUILD_ONLY'
            proof['device_inference_verified'] = False
        else:
            device_run(args, payload, inputs, proof)
            proof['device_inference_verified'] = True
        recheck(inputs)
    proof['host_temporary_cleanup_verified'] = not Path(temp).exists()
    if not proof['host_temporary_cleanup_verified']:
        raise RuntimeError('Host cleanup failed')
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents accidental replacement if a concurrent run chose the same path.
    with args.receipt.open('x') as stream:
        stream.write(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof, sort_keys=True))


if __name__ == '__main__':
    main()

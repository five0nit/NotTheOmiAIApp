#!/usr/bin/env python3
"""Run synthetic Android acceptance ONLY on the existing isolated emulator.

Never clears/uninstalls app data. Release/test APKs retain the existing signer.
Use only a disposable emulator whose library contains synthetic test data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'app.nottheomi.ai'


def run(*argv, timeout=180):
    result = subprocess.run([str(x) for x in argv], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Command failed: '+str(argv[0])+'\n'+result.stdout+result.stderr)
    return result.stdout + result.stderr


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def field(pattern, value):
    match = re.search(pattern, value)
    if not match:
        raise RuntimeError('Missing expected metadata field: '+pattern)
    return match.group(1).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--key-dir', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--class-name', help='Optional exact test class subset')
    args = parser.parse_args()
    if args.receipt.exists():
        parser.error('Use a fresh receipt path')
    sdk = args.sdk.resolve()
    adb = [str(sdk/'platform-tools/adb'), '-P', '5043', '-s', 'emulator-5580']
    shell = lambda *items, **kw: run(*adb, 'shell', *items, **kw).strip()
    if shell('getprop', 'ro.kernel.qemu') != '1' or shell('getprop', 'ro.product.model') != 'sdk_gphone64_x86_64':
        raise SystemExit('Refusing instrumentation outside exact synthetic emulator')
    services = shell('dumpsys', 'activity', 'services', PACKAGE)
    if any(name in services for name in ('CaptureService', 'OmiCaptureService', 'RefinementJobService')):
        raise SystemExit('App service active; not interrupting it')
    version = field(r'versionName\s+[\"\']([^\"\']+)', (ROOT/'app/build.gradle').read_text())
    apk = ROOT/'dist'/f'NotTheOmiAIApp-{version}-x86_64.apk'
    original_test = ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    tools = sdk/'build-tools/34.0.0'
    key, password = args.key_dir/'release.p12', args.key_dir/'password'
    if not key.is_file() or not password.is_file():
        raise SystemExit('Existing signing identity missing; refusing replacement')
    signature = run(tools/'apksigner', 'verify', '--print-certs', apk)
    before = shell('dumpsys', 'package', PACKAGE)
    identities = lambda text: {name: field(r'\b'+name+r'=(.+)', text) for name in ('userId', 'firstInstallTime')}
    before_identity = identities(before)
    with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-test-sign-') as directory:
        aligned = Path(directory)/'aligned.apk'
        signed = Path(directory)/'instrumentation.apk'
        run(tools/'zipalign', '-f', '-p', '4', original_test, aligned)
        run(tools/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'release', '--ks-pass', 'file:'+str(password), '--out', signed, aligned)
        test_signature = run(tools/'apksigner', 'verify', '--print-certs', signed)
        cert = lambda value: field(r'Signer #1 certificate SHA-256 digest: (\w+)', value)
        if cert(signature) != cert(test_signature):
            raise RuntimeError('Instrumentation certificate differs')
        for selected in (apk, signed):
            if 'Success' not in run(*adb, 'install', '-r', selected):
                raise RuntimeError('In-place emulator install did not report success')
        installed = shell('pm', 'path', PACKAGE)
        paths = [line.removeprefix('package:') for line in installed.splitlines() if line.startswith('package:')]
        if len(paths) != 1 or shell('sha256sum', paths[0]).split()[0] != digest(apk):
            raise RuntimeError('Installed emulator release bytes differ')
        after_identity = identities(shell('dumpsys', 'package', PACKAGE))
        if before_identity != after_identity:
            raise RuntimeError('Emulator identity changed')
        command = ['am', 'instrument', '-w', '-r']
        if args.class_name:
            command += ['-e', 'class', args.class_name]
        command += [PACKAGE+'.test/android.test.InstrumentationTestRunner']
        output = shell(*command, timeout=600)
        log = args.receipt.with_suffix('.log')
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(output+'\n')
        match = re.search(r'OK \((\d+) tests?\)', output)
        if not match or any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')):
            raise RuntimeError('Android suite failed; see '+str(log))
        result = {'result':'PASS_HYBRID_ANDROID', 'scope':'exact signed release on synthetic-only emulator, not wearable acceptance', 'tests':int(match.group(1)), 'artifact':str(apk), 'sha256':digest(apk), 'installed_bytes_verified':True, 'identity_unchanged':True, 'certificate_sha256':cert(signature), 'log':str(log), 'test_apk_sha256':digest(signed), 'class':args.class_name}
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    with args.receipt.open('x') as stream:
        stream.write(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()

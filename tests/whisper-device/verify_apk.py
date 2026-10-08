#!/usr/bin/env python3
"""Verify the built release contract without loading model code or accessing a device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((ROOT / 'DEPENDENCIES.json').read_text())
    model = metadata['model']
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate ZIP entries'
        assert 'assets/model.zip' not in names, 'Retired Vosk model still bundled'
        assert not any(n in names for n in ['assets/licenses/vosk-license.txt', 'assets/licenses/jna-license.txt']), 'Retired runtime notices still bundled'
        assert not any(n.endswith('/test.wav') or n.endswith('/jfk.wav') for n in names), 'Public fixture in release'
        assert not any('vosk' in n.lower() and n.endswith('.so') for n in names), 'Vosk native library still bundled'
        assert not any('jnidispatch' in n.lower() for n in names), 'JNA still bundled'
        member = 'assets/' + model['filename']
        info = archive.getinfo(member)
        assert info.file_size == model['bytes'], 'Bundled model size mismatch'
        assert info.compress_type == zipfile.ZIP_STORED, 'Model must not be compressed in APK'
        digest = hashlib.sha256()
        with archive.open(member) as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(block)
        assert digest.hexdigest() == model['sha256'], 'Bundled model checksum mismatch'
        for license_name in ['whisper.cpp-MIT.txt', 'whisper-model-MIT.txt', 'concentus-license.txt', 'omi-license.txt']:
            assert len(archive.read('assets/licenses/' + license_name)) > 100
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
        assert abis and set(abis) <= {'arm64-v8a', 'x86_64'}, 'Unexpected native ABI set'
        for abi in abis:
            libraries = [n for n in names if n.startswith('lib/' + abi + '/') and n.endswith('.so')]
            expected = 'lib/' + abi + '/libnottheomi-whisper.so'
            assert libraries == [expected], 'Expected exactly one statically linked Whisper JNI'
            native_receipt = json.loads((ROOT / 'verification/whisper-native-build.json').read_text())
            assert hashlib.sha256(archive.read(expected)).hexdigest() == native_receipt['abis'][abi]['sha256'], 'JNI bytes differ from audited native build'
        for name in names:
            if name.endswith('.dex'):
                data = archive.read(name)
                assert b'Lorg/vosk/' not in data, 'Vosk Java classes remain in APK'
                assert b'Lcom/sun/jna/' not in data, 'JNA Java classes remain in APK'
    aapt = args.sdk / 'build-tools/34.0.0/aapt'
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(args.apk)], text=True)
    permissions = subprocess.check_output([str(aapt), 'dump', 'permissions', str(args.apk)], text=True)
    assert "package: name='app.nottheomi.ai'" in badging
    assert "versionCode='7'" in badging and "versionName='0.4.0'" in badging
    assert 'application-debuggable' not in badging
    assert 'android.permission.INTERNET' not in permissions
    digest = hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest()
    print(json.dumps({'result': 'PASS_WHISPER_RELEASE_PACKAGE', 'artifact': str(args.apk.resolve()),
                      'sha256': digest, 'bytes': args.apk.stat().st_size, 'abis': abis,
                      'model': model['name'], 'model_sha256': model['sha256'],
                      'vosk_jna_removed': True, 'internet_permission': False}))


if __name__ == '__main__':
    main()

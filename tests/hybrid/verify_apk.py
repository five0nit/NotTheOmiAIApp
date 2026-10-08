#!/usr/bin/env python3
"""Verify actual hybrid APK weights, native bytes, offline permissions and signing."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
ROOT = Path(__file__).resolve().parents[2]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((ROOT/'DEPENDENCIES.json').read_text())
    native = json.loads((ROOT/'verification/whisper-native-build.json').read_text())
    runtime_native = {}
    for dependency in metadata['runtime']:
        coordinate = dependency.get('coordinate')
        if not coordinate:
            continue
        group, artifact, version = coordinate.removesuffix('@aar').split(':')
        matches = list((Path.home()/'.gradle/caches/modules-2/files-2.1'/group/artifact/version).rglob('*.aar'))
        assert len(matches) == 1, 'Expected one exact cached runtime artifact'
        assert sha(matches[0].read_bytes()) == dependency['sha256'], 'Runtime AAR pin mismatch'
        with zipfile.ZipFile(matches[0]) as source:
            for name in source.namelist():
                if name.startswith('jni/') and name.endswith('.so'):
                    runtime_native['lib/'+name.removeprefix('jni/')] = sha(source.read(name))
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK members'
        assert not any(n.endswith('/jfk.wav') or n.endswith('/test.wav') for n in names), 'Test fixture in release'
        for key in ['model', 'preview_model']:
            model = metadata[key]
            member = 'assets/'+model['filename']
            info = archive.getinfo(member)
            assert info.file_size == model['bytes'], 'Wrong model length'
            with archive.open(member) as source:
                digest = hashlib.sha256()
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(block)
                assert digest.hexdigest() == model['sha256'], 'Wrong model bytes'
            if key == 'model':
                assert info.compress_type == zipfile.ZIP_STORED, 'Whisper model compressed'
        for name in ['whisper.cpp-MIT.txt','whisper-model-MIT.txt','vosk-license.txt','jna-license.txt','concentus-license.txt','omi-license.txt']:
            assert len(archive.read('assets/licenses/'+name)) > 100, 'Missing license'
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
        assert abis and set(abis) <= {'arm64-v8a','x86_64'}, 'Unintended ABI'
        actual_native = {}
        for abi in abis:
            whisper = 'lib/'+abi+'/libnottheomi-whisper.so'
            expected = {name:value for name,value in runtime_native.items() if name.startswith('lib/'+abi+'/')}
            expected[whisper] = native['abis'][abi]['sha256']
            actual = {name:sha(archive.read(name)) for name in names if name.startswith('lib/'+abi+'/') and name.endswith('.so')}
            assert actual == expected, 'APK native libraries differ from pinned inputs'
            actual_native.update(actual)
        dex = b'\n'.join(archive.read(name) for name in names if name.endswith('.dex'))
        for class_name in ['Lorg/vosk/Recognizer;','Lcom/sun/jna/Native;','Lapp/nottheomi/ai/RefinementJobService;','Lapp/nottheomi/ai/RefinementEngine;','Lapp/nottheomi/ai/PreviewRecognizer;']:
            assert class_name.encode() in dex, 'Missing runtime class '+class_name
    tools = args.sdk/'build-tools/34.0.0'
    def run(*command):
        return subprocess.check_output([str(x) for x in command], text=True)
    badging = run(tools/'aapt','dump','badging',args.apk)
    permissions = run(tools/'aapt','dump','permissions',args.apk)
    manifest = run(tools/'aapt','dump','xmltree',args.apk,'AndroidManifest.xml')
    signature = run(tools/'apksigner','verify','--verbose','--print-certs',args.apk)
    run(tools/'zipalign','-c','-p','4',args.apk)
    assert "package: name='app.nottheomi.ai'" in badging
    gradle = (ROOT/'app/build.gradle').read_text()
    code_match = re.search(r'\bversionCode\s+(\d+)', gradle)
    name_match = re.search(r"\bversionName\s+['\"]([^'\"]+)['\"]", gradle)
    assert code_match and name_match, 'Missing canonical Gradle release version'
    assert f"versionCode='{code_match.group(1)}'" in badging and f"versionName='{name_match.group(1)}'" in badging
    assert 'application-debuggable' not in badging
    assert 'android.permission.INTERNET' not in permissions
    assert 'android.permission.BIND_JOB_SERVICE' in manifest and 'RefinementJobService' in manifest
    assert 'Verified using v2 scheme' in signature
    with args.apk.open('rb') as source:
        digest = hashlib.file_digest(source,'sha256').hexdigest()
    print(json.dumps({'result':'PASS_HYBRID_RELEASE_PACKAGE','artifact':str(args.apk.resolve()),'sha256':digest,'bytes':args.apk.stat().st_size,'abis':abis,'native_sha256':actual_native,'model_sha256':{key:metadata[key]['sha256'] for key in ['model','preview_model']},'internet_permission':False,'signature_verified':True,'alignment_verified':True}))


if __name__ == '__main__':
    main()

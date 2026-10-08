#!/usr/bin/env python3
"""Run production archive/queue code in a disposable separate Android package.

Synthetic SQLite/Keystore only; no production process, preferences or library access.
Existing capture/refinement must be idle. No BLE/microphone permissions. Refuse to
replace an existing probe package; remove only our newly installed probe afterward.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'app.nottheomi.archiveprobe'
PRODUCTION = 'app.nottheomi.ai'
SOURCES = ['app/src/main/java/app/nottheomi/ai/Recordings.java',
           'app/src/main/java/app/nottheomi/ai/OmiPcmQueue.java',
           'app/src/androidTest/java/app/nottheomi/ai/ArchiveThroughputTest.java']


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(argv, timeout=120):
    result = subprocess.run(list(map(str, argv)), capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): '+result.stdout[-3000:]+result.stderr[-3000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--key-dir', type=Path, required=True)
    parser.add_argument('--server', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--sustained', action='store_true', help='Populated 60-second capture and concurrent library-read cases')
    args = parser.parse_args()
    if args.receipt.exists():
        parser.error('Fresh receipt path required')
    tools = args.sdk/'build-tools/34.0.0'
    platform = args.sdk/'platforms/android-34'
    android = platform/'android.jar'
    libraries = [android, *(platform/'optional').glob('android.test.*.jar')]
    adb = [str(args.sdk/'platform-tools/adb'), '-P', args.server, '-s', args.serial]
    shell = lambda *parts, **kw: run([*adb, 'shell', *parts], **kw)

    def idle():
        services = shell('dumpsys', 'activity', 'services', PRODUCTION)
        if 'ACTIVITY MANAGER SERVICES' not in services or any(
                name in services for name in ('CaptureService', 'RefinementJobService')):
            raise RuntimeError('Production service active or state unknown; no probe run')

    idle()
    if 'package:'+PACKAGE in shell('pm', 'list', 'packages', PACKAGE):
        raise RuntimeError('Existing probe package; refusing replacement or deletion')
    before = shell('dumpsys', 'package', PRODUCTION)
    production_pid = shell('pidof', PRODUCTION)
    identity = lambda text: {key: re.search(r'\b'+key+r'=(.+)', text).group(1)
                             for key in ('appId', 'firstInstallTime', 'versionName')}
    before_identity = identity(before)
    sources_to_test = list(SOURCES)
    if args.sustained:
        sources_to_test[-1] = 'app/src/androidTest/java/app/nottheomi/ai/ArchiveSustainedThroughputTest.java'
    source_hashes = {rel: digest(ROOT/rel) for rel in sources_to_test}
    installed = False
    with tempfile.TemporaryDirectory(prefix='nottheomi-archive-probe-') as directory:
        work = Path(directory)
        manifest = work/'AndroidManifest.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<application android:label="Synthetic archive diagnostic" android:allowBackup="false" android:debuggable="false">
<uses-library android:name="android.test.runner" android:required="true"/>
<uses-library android:name="android.test.base" android:required="true"/>
</application>
<instrumentation android:name="android.test.InstrumentationTestRunner" android:targetPackage="{PACKAGE}"/>
</manifest>''')
        classes, dex, snapshots = work/'classes', work/'dex', work/'sources'
        for path in (classes, dex, snapshots):
            path.mkdir()
        sources = []
        for rel in sources_to_test:
            target = snapshots/Path(rel).name
            target.write_bytes((ROOT/rel).read_bytes())
            assert digest(target) == source_hashes[rel]
            sources.append(target)
        run(['javac', '--release', '17', '-cp', ':'.join(map(str, libraries)), '-d', classes, *sources])
        run([tools/'d8', '--lib', android, '--min-api', '26', '--output', dex, *classes.rglob('*.class')])
        unsigned, aligned, signed = work/'unsigned.apk', work/'aligned.apk', work/'probe.apk'
        run([tools/'aapt', 'package', '-f', '-M', manifest, '-I', android, '-F', unsigned])
        with zipfile.ZipFile(unsigned, 'a') as archive:
            archive.write(dex/'classes.dex', 'classes.dex')
        run([tools/'zipalign', '-f', '-p', '4', unsigned, aligned])
        key, password = args.key_dir/'release.p12', args.key_dir/'password'
        if not key.is_file() or not password.is_file():
            raise RuntimeError('Existing signing key unavailable')
        run([tools/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'release',
             '--ks-pass', 'file:'+str(password), '--out', signed, aligned])
        run([tools/'apksigner', 'verify', signed])
        permissions = re.findall(r"uses-permission: name='([^']+)'", run([tools/'aapt', 'dump', 'permissions', signed]))
        assert permissions == ['android.permission.WAKE_LOCK']
        idle()
        try:
            installed = True
            assert 'Success' in run([*adb, 'install', signed])
            installed_path = shell('pm', 'path', PACKAGE).removeprefix('package:')
            assert shell('sha256sum', installed_path).split()[0] == digest(signed)
            idle()
            # A unique shell log marker bounds this invocation without clearing shared logs.
            run_id = 'probe_' + uuid.uuid4().hex
            shell('log', '-t', 'OmiArchiveProbe', run_id)
            test_class = 'ArchiveSustainedThroughputTest' if args.sustained else 'ArchiveThroughputTest'
            output = shell('am', 'instrument', '-w', '-r', '-e', 'class',
                           'app.nottheomi.ai.' + test_class,
                           PACKAGE+'/android.test.InstrumentationTestRunner', timeout=1200 if args.sustained else 240)
            args.receipt.with_suffix('.log').write_text(output+'\n')
            expected_tests = 2 if args.sustained else 3
            passed = bool(re.search(r'OK \('+str(expected_tests)+r' tests\)', output))
            log = shell('logcat', '-d', '-s', 'OmiArchiveProbe:I', '*:S')
            rows = []
            assert run_id in log, 'Missing invocation boundary'
            for line in log.split(run_id, 1)[1].splitlines():
                if 'OmiArchiveProbe:' in line:
                    values = {k: int(v) for k,v in re.findall(r'(\w+)=(-?\d+)', line)}
                    if values:
                        rows.append(values)
            assert len(rows) == expected_tests, 'Missing or ambiguous measurement records'
            if args.sustained:
                assert sorted(row['browse'] for row in rows) == [0, 1]
            else:
                assert sorted(row['history'] for row in rows) == [0, 12, 48]
            after = shell('dumpsys', 'package', PRODUCTION)
            assert identity(after) == before_identity, 'Production identity changed'
            after_pid = shell('pidof', PRODUCTION)
            assert after_pid == production_pid, 'Production process changed during probe'
            assert all(digest(ROOT/rel) == sha for rel,sha in source_hashes.items()), 'Source changed'
            proof = {'result':'PASS' if passed else 'FAIL', 'scope':'synthetic PCM, production queue/archive source in separate diagnostic package; not physical BLE acceptance',
                     'source_sha256':source_hashes, 'metrics':rows,
                     'production_identity_unchanged':True, 'production_pid_unchanged':True,
                     'apk_sha256':digest(signed)}
        finally:
            if installed:
                result = run([*adb, 'uninstall', PACKAGE])
                assert 'Success' in result
                assert 'package:'+PACKAGE not in shell('pm', 'list', 'packages', PACKAGE)
        proof['probe_removed_verified'] = True
    args.receipt.write_text(json.dumps(proof, indent=2)+'\n')
    print(json.dumps(proof))
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

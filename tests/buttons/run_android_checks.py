#!/usr/bin/env python3
"""Exact-build synthetic Android checks. Never targets a physical phone or clears data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'app.nottheomi.ai'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory():
    paths = sorted(p for p in (ROOT/'app/src').rglob('*') if p.is_file())
    paths.append(ROOT/'app/build.gradle')
    return {str(p.relative_to(ROOT)): digest(p) for p in paths}


def instrumentation_result(out, expected_methods):
    """Count completed method status records, including partial failing runs."""
    completed = []
    method = None
    for line in out.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: test='):
            method = line.split('=', 1)[1].strip()
        elif line.startswith('INSTRUMENTATION_STATUS_CODE:'):
            code = int(line.split(':', 1)[1])
            if method is not None and code != 1:
                completed.append({'method': method, 'status_code': code})
            method = None
    match = re.search(r'OK \((\d+) tests?\)', out)
    methods = sorted(set(re.findall(r'INSTRUMENTATION_STATUS: test=(test\w+)', out)))
    successes = [entry['method'] for entry in completed if entry['status_code'] == 0]
    failures = [entry for entry in completed if entry['status_code'] != 0]
    passed = (bool(match) and int(match.group(1)) == len(expected_methods)
              and methods == expected_methods and sorted(successes) == expected_methods
              and not failures
              and not any(x in out for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))
    return {'expected_tests': len(expected_methods), 'passed_tests': len(successes),
            'observed_methods': methods, 'failed_methods': failures,
            'result': 'PASS' if passed else 'FAIL'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--classes', nargs='+', required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [str(args.sdk/'platform-tools/adb'), '-H', '127.0.0.1', '-P', '5043', '-s', 'emulator-5580']
    receipt = {'result': 'RUNNING', 'scope': 'Exact debug APK; isolated synthetic emulator, not wearable/production-phone acceptance',
               'source_sha256': inventory(), 'installs': [], 'classes': []}

    def save():
        (args.output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')

    def run(*parts, timeout=90):
        result = subprocess.run([*adb, *map(str, parts)], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+str(parts[0])+' '+result.stdout+result.stderr)
        return result.stdout+result.stderr

    def shell(*parts, **kw):
        return run('shell', *parts, **kw).strip()

    def identity():
        value = shell('dumpsys', 'package', PACKAGE)
        fields = {}
        for name in ('userId', 'firstInstallTime'):
            match = re.search(r'\b'+name+r'=(.+)', value)
            if not match:
                raise RuntimeError('Existing synthetic app identity missing: '+name)
            fields[name] = match.group(1).strip()
        return fields

    def installed_hash(package):
        paths = [line[8:] for line in shell('pm', 'path', package).splitlines() if line.startswith('package:')]
        if len(paths) != 1:
            raise RuntimeError('Expected one installed APK: '+package)
        return shell('sha256sum', paths[0]).split()[0]

    save()
    try:
        assert shell('getprop', 'ro.kernel.qemu') == '1', 'Synthetic emulator required'
        assert shell('getprop', 'ro.hardware') in ('ranchu', 'goldfish'), 'Synthetic hardware required'
        assert shell('getprop', 'ro.product.model') == 'sdk_gphone64_x86_64', 'Exact disposable model required'
        services = shell('dumpsys', 'activity', 'services', PACKAGE)
        assert not any(name in services for name in ('CaptureService', 'OmiCaptureService', 'RefinementJobService')), 'App service active; refusing interruption'
        before = identity()
        apks = [(PACKAGE, ROOT/'app/build/outputs/apk/debug/app-x86_64-debug.apk'),
                (PACKAGE+'.test', ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
        for package, apk in apks:
            started = time.monotonic()
            expected = digest(apk)
            out = run('install', '--no-streaming', '-r', '-t', apk, timeout=600)
            assert 'Success' in out, 'In-place install did not report success'
            assert installed_hash(package) == expected, 'Installed bytes differ: '+package
            receipt['installs'].append({'package': package, 'file': str(apk), 'sha256': expected,
                                         'seconds': round(time.monotonic()-started, 2), 'installed_bytes_verified': True})
            save()
        assert identity() == before, 'Synthetic app identity changed'
        receipt['identity_unchanged'] = True
        shell('pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        for name in args.classes:
            assert re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*Test', name), 'Invalid test class name'
            source = ROOT/f'app/src/androidTest/java/app/nottheomi/ai/{name}.java'
            expected_methods = sorted(re.findall(r'public\s+void\s+(test\w+)\s*\(', source.read_text()))
            assert expected_methods, 'No expected tests in '+name
            started = time.monotonic()
            out = shell('am', 'instrument', '-w', '-r', '-e', 'class', PACKAGE+'.'+name,
                        PACKAGE+'.test/android.test.InstrumentationTestRunner', timeout=1800)
            log = args.output/(name+'.log')
            log.write_text(out+'\n')
            result = instrumentation_result(out, expected_methods)
            passed = result['result'] == 'PASS'
            receipt['classes'].append({'class': name, **result,
                                        'seconds': round(time.monotonic()-started, 2), 'log': str(log)})
            save()
            print(json.dumps(receipt['classes'][-1]), flush=True)
            assert passed, 'Instrumentation class failed: '+name
        assert inventory() == receipt['source_sha256'], 'Source changed during execution'
        for entry in receipt['installs']:
            assert digest(Path(entry['file'])) == entry['sha256'], 'Build artifact changed during execution'
            assert installed_hash(entry['package']) == entry['sha256'], 'Installed artifact changed during execution'
        receipt['total_tests'] = sum(c['passed_tests'] for c in receipt['classes'])
        receipt['result'] = 'PASS'
        print(json.dumps({'result': 'PASS', 'total_tests': receipt['total_tests'], 'receipt': str(args.output/'receipt.json')}), flush=True)
    except Exception as exc:
        receipt['result'] = 'FAIL'
        receipt['error'] = str(exc)
        raise
    finally:
        save()


if __name__ == '__main__':
    main()

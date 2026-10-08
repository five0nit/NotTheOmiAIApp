#!/usr/bin/env python3
"""Compose unchanged OmiCaptureService + OmiBle + vendored Concentus on a JVM.

Only fixture dictionaries are extracted from sibling scripts via ast.literal_eval;
neither script is imported/executed. No Android SDK, Gradle, ADB or dependencies.
"""
import argparse
import ast
import pathlib
import subprocess
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
SOURCE = ROOT / 'app/src/main/java'
SCENARIOS = ('startup_retry', 'sequence_loss', 'malformed_opus', 'link_recovery', 'stop_backoff',
             'recovery_before_deadline', 'recovery_at_deadline', 'startup_at_deadline')


def literal(path, name):
    tree = ast.parse(path.read_text())
    return ast.literal_eval(next(node.value for node in tree.body
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, help='run one focused scenario')
    parser.add_argument('--service-ref', help='negative-control service source from this local Git ref (read-only)')
    args = parser.parse_args()
    scenarios = (args.scenario,) if args.scenario else SCENARIOS
    stubs = literal(ROOT / 'tests/capture/run_host_checks.py', 'STUBS')
    stubs = {key: value for key, value in stubs.items() if not key.startswith('android/media/')}
    stubs.pop('app/nottheomi/ai/OmiCaptureService.java')  # Use the production composition owner.
    tree = ast.parse((ROOT / 'tests/omi-capture/run_host_checks.py').read_text())
    updates = next(node.value.args[0] for node in tree.body if isinstance(node, ast.Expr)
        and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute)
        and isinstance(node.value.func.value, ast.Name) and node.value.func.value.id == 'STUBS'
        and node.value.func.attr == 'update')
    stubs.update(ast.literal_eval(updates))
    context = stubs['android/content/Context.java']
    context = context.rsplit('}', 1)[0] + '''
 public static final String BLUETOOTH_SERVICE="bluetooth";
 public final android.bluetooth.BluetoothAdapter adapter=new android.bluetooth.BluetoothAdapter();
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String name){return new android.bluetooth.BluetoothManager(adapter);}
}'''
    stubs.update(literal(ROOT / 'tests/omi/run_button_ble_tests.py', 'SOURCES'))
    stubs['android/content/Context.java'] = context
    stubs['android/os/SystemClock.java'] = '''package android.os; public class SystemClock {
 public static long elapsedRealtime(){return 1000+Handler.now();}
}'''

    stubs['android/app/Service.java'] = stubs['android/app/Service.java'].replace(
        'public void startForeground(int id,Notification n,int type){foregroundStarts++;}',
        'public void startForeground(int id,Notification n,int type){if(type!=16)throw new AssertionError("wrong FGS type");foregroundStarts++;}')
    for path in (HERE / 'stubs').rglob('*.java'):
        stubs[path.relative_to(HERE / 'stubs').as_posix()] = path.read_text()
    production = [SOURCE / 'app/nottheomi/ai' / name for name in
        ('OmiCaptureService.java', 'OmiBle.java', 'ButtonEvent.java', 'OmiPcmQueue.java', 'LiveTranscript.java')]
    production += sorted((SOURCE / 'org/concentus').glob('*.java'))
    with tempfile.TemporaryDirectory(prefix='omi-composition-') as tmp:
        work = pathlib.Path(tmp)
        java, classes = work / 'src', work / 'classes'
        classes.mkdir()
        for name, value in stubs.items():
            target = java / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(textwrap.dedent(value))
        if args.service_ref:
            original = subprocess.run(['git', 'show',
                f'{args.service_ref}:app/src/main/java/app/nottheomi/ai/OmiCaptureService.java'],
                cwd=ROOT, check=True, capture_output=True, text=True, timeout=15).stdout
            replacement = work / 'negative-control' / 'OmiCaptureService.java'
            replacement.parent.mkdir()
            replacement.write_text(original)
            production[0] = replacement
            print(f'NEGATIVE CONTROL: service from {args.service_ref}; all other production sources from working tree.', flush=True)
        subprocess.run(['javac', '--release', '17', '-d', str(classes),
            *map(str, sorted(java.rglob('*.java'))), *map(str, production),
            str(HERE / 'OmiCompositionHostTest.java')], check=True, timeout=120)
        for scenario in scenarios:
            subprocess.run(['java', '-cp', str(classes), 'app.nottheomi.ai.OmiCompositionHostTest', scenario],
                check=True, timeout=25)
    print(f'Omi composition host PASS: {len(scenarios)} scenarios; real service + BLE + Concentus; fake Android/GATT/storage/preview.')


if __name__ == '__main__':
    main()

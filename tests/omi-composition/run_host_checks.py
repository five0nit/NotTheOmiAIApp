#!/usr/bin/env python3
"""Compose unchanged OmiCaptureService + OmiBle + vendored Concentus on a JVM.

Only fixture dictionaries are extracted from sibling scripts via ast.literal_eval;
neither script is imported/executed. No Android SDK, Gradle, ADB or dependencies.
"""
import argparse
import ast
import pathlib
import re
import subprocess
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
SOURCE = ROOT / 'app/src/main/java'
ARCHIVE_SCENARIOS = ('startup_retry', 'sequence_loss', 'malformed_opus', 'link_recovery', 'disconnect_backoff',
                     'recovery_before_deadline', 'recovery_long_absence', 'startup_at_deadline', 'diagnostics',
                     'queue_overload_recovery', 'radio_gap_capacity_overlap')
CONTROLS_SCENARIOS = ('controls_idle', 'controls_start_exit', 'controls_toggle_exit',
                      'controls_stop_standby', 'controls_disconnect_drain', 'controls_disconnect_transition',
                      'controls_stop_backoff', 'controls_stale_gatt', 'controls_stale_listener',
                      'controls_live_buttons', 'controls_prepcm_stop', 'controls_fatal_stop')
CONTROLS_SCENARIOS += ('controls_media', 'capture_media')
CONTROLS_SCENARIOS += ('controls_triple', 'capture_triple', 'controls_triple_stale')
CONTROLS_SCENARIOS += ('controls_configuration_queue', 'capture_configuration_queue')
CONTROLS_SCENARIOS += ('controls_multi_taps', 'capture_multi_taps', 'controls_smart', 'capture_smart',
                       'controls_pause_start', 'capture_pause_start', 'controls_flashlight', 'capture_flashlight')
CONTROLS_SCENARIOS += ('controls_reject_phone', 'controls_reject_permission', 'controls_reject_notification',
                       'controls_stop_transition', 'controls_stop_intent_transition')
SCENARIOS = ARCHIVE_SCENARIOS + CONTROLS_SCENARIOS


def literal(path, name):
    tree = ast.parse(path.read_text())
    return ast.literal_eval(next(node.value for node in tree.body
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS + ('stop_backoff',), help='run one focused scenario (stop_backoff aliases disconnect_backoff)')
    parser.add_argument('--service-ref', help='negative-control service source from this local Git ref (read-only)')
    args = parser.parse_args()
    scenario = 'disconnect_backoff' if args.scenario == 'stop_backoff' else args.scenario
    scenarios = (scenario,) if scenario else (ARCHIVE_SCENARIOS if args.service_ref else SCENARIOS)
    if args.service_ref and scenario in CONTROLS_SCENARIOS:
        parser.error('--service-ref covers historical archive scenarios only; controls requires the current service API')
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
        ('OmiCaptureService.java', 'OmiBle.java', 'ButtonEvent.java', 'ButtonGestures.java', 'HapticPatterns.java', 'OmiPcmQueue.java', 'LiveTranscript.java')]
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
        tests = [HERE / 'OmiCompositionHostTest.java']
        if any(s in CONTROLS_SCENARIOS for s in scenarios):
            tests.append(HERE / 'ControlsCompositionHostTest.java')
        subprocess.run(['javac', '--release', '17', '-d', str(classes),
            *map(str, sorted(java.rglob('*.java'))), *map(str, production),
            *map(str, tests)], check=True, timeout=120)
        assertions = 0
        for scenario in scenarios:
            test_class = 'ControlsCompositionHostTest' if scenario in CONTROLS_SCENARIOS else 'OmiCompositionHostTest'
            result = subprocess.run(['java', '-cp', str(classes), f'app.nottheomi.ai.{test_class}', scenario],
                capture_output=True, text=True, timeout=25)
            print(result.stdout, end='', flush=True)
            if result.stderr:
                print(result.stderr, end='', flush=True)
            result.check_returncode()
            counts = re.findall(rf'{test_class} PASS {re.escape(scenario)}: (\d+) assertions;', result.stdout)
            if len(counts) != 1:
                raise AssertionError(f'missing/duplicate assertion receipt for {scenario}')
            assertions += int(counts[0])
    print(f'Omi composition host PASS: {len(scenarios)} scenarios; {assertions} assertions; real service + BLE + Concentus; fake Android/GATT/storage/preview.')


if __name__ == '__main__':
    main()

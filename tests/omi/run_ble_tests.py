#!/usr/bin/env python3
"""Host-only transport fixtures: real Java transport/codec, fake Android/GATT.

Requires Python 3 and JDK 17+. No Gradle, Android SDK, network or device access.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
sys.dont_write_bytecode = True
from run_button_ble_tests import ROOT, SOURCES

CLASSES = ('ButtonBleTest', 'LedBleTest', 'BatteryBleTest', 'ReconnectBleTest', 'TransportBleTest')

def main():
    selected = sys.argv[1:] or CLASSES
    if any(name not in CLASSES for name in selected):
        raise SystemExit('Unknown fixture; choose: ' + ', '.join(CLASSES))
    src = ROOT / 'app/src/main/java'
    with tempfile.TemporaryDirectory(prefix='omi-ble-host-') as tmp:
        work = Path(tmp)
        stubs = []
        for name, text in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(text))
            stubs.append(path)
        production = [src / 'app/nottheomi/ai/OmiBle.java', src / 'app/nottheomi/ai/ButtonEvent.java']
        codec = sorted((src / 'org/concentus').glob('*.java'))
        assert len(codec) == 124, f'Expected 124 decoder sources, found {len(codec)}'
        tests = [ROOT / 'tests/omi' / (name + '.java') for name in CLASSES]
        subprocess.run(['javac', '-d', str(work), *map(str, stubs + production + codec + tests)],
                       check=True, timeout=120)
        for name in selected:
            subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.' + name],
                           check=True, timeout=60)
    print('HOST-ONLY PASS: ' + ', '.join(selected))

if __name__ == '__main__':
    main()

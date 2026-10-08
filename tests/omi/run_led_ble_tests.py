#!/usr/bin/env python3
"""Host-only LED tests: actual OmiBle/Concentus, shared single-flight GATT fake."""
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
sys.dont_write_bytecode = True
from run_button_ble_tests import ROOT, SOURCES


def main():
    src = ROOT / 'app/src/main/java'
    with tempfile.TemporaryDirectory(prefix='omi-led-ble-tests-') as tmp:
        work = Path(tmp)
        stubs = []
        for name, text in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(text))
            stubs.append(path)
        production = [src / 'app/nottheomi/ai/OmiBle.java', src / 'app/nottheomi/ai/ButtonEvent.java']
        production += sorted((src / 'org/concentus').glob('*.java'))
        tests = [ROOT / 'tests/omi/ButtonBleTest.java', ROOT / 'tests/omi/LedBleTest.java']
        subprocess.run(['javac', '-d', str(work), *map(str, stubs), *map(str, production),
                        *map(str, tests)], check=True, timeout=120)
        subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.LedBleTest'], check=True, timeout=60)


if __name__ == '__main__':
    main()

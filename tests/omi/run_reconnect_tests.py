#!/usr/bin/env python3
"""Host-only reconnect regressions; reuse existing GATT/Handler fake fixtures."""
from pathlib import Path
import subprocess
import tempfile
import textwrap
import sys
sys.dont_write_bytecode = True
from run_button_ble_tests import ROOT, SOURCES


def main():
    src = ROOT / 'app/src/main/java'
    with tempfile.TemporaryDirectory(prefix='omi-reconnect-tests-') as tmp:
        work = Path(tmp)
        stubs = []
        for name, text in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(text))
            stubs.append(path)
        production = [src / 'app/nottheomi/ai/OmiBle.java', src / 'app/nottheomi/ai/ButtonEvent.java']
        production += sorted((src / 'org/concentus').glob('*.java'))
        tests = [ROOT / 'tests/omi/ButtonBleTest.java', ROOT / 'tests/omi/ReconnectBleTest.java']
        subprocess.run(['javac', '-d', str(work), *map(str, stubs), *map(str, production),
                        *map(str, tests)], check=True, timeout=120)
        subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.ReconnectBleTest'], check=True, timeout=60)


if __name__ == '__main__':
    main()

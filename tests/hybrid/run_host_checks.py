#!/usr/bin/env python3
"""Compile/run bounded refinement orchestration without Android or native models."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-host-') as work:
    sources = [ROOT/'app/src/main/java/app/nottheomi/ai/RefinementEngine.java', ROOT/'tests/hybrid/RefinementEngineHostTest.java']
    subprocess.run(['javac','--release','17','-d',work,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',work,'app.nottheomi.ai.RefinementEngineHostTest'],check=True)

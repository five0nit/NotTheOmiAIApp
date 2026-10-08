#!/usr/bin/env python3
"""Prepare both pinned offline engines: streaming Vosk preview and final Whisper."""
from pathlib import Path
import runpy

if __name__ == '__main__':
    runpy.run_path(str(Path(__file__).with_name('prepare_whisper.py')), run_name='__main__')
    runpy.run_path(str(Path(__file__).with_name('prepare_preview.py')), run_name='__main__')

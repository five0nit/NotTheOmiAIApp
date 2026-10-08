#!/usr/bin/env python3
"""Prepare the pinned preview model; never accept unverified cached/downloaded bytes."""
import hashlib
import json
import os
from pathlib import Path

import tempfile
import urllib.request
ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    spec = json.loads((ROOT/'DEPENDENCIES.json').read_text())['preview_model']
    target = ROOT/'app/src/main/assets'/spec['filename']
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='preview-model-', suffix='.part', dir=target.parent)
        temporary = Path(name)
        try:
            with os.fdopen(fd, 'wb') as out, urllib.request.urlopen(spec['url'], timeout=120) as response:
                count = 0
                for chunk in iter(lambda: response.read(1024*1024), b''):
                    count += len(chunk)
                    if count > spec['bytes']:
                        raise ValueError('Preview model exceeded pinned length')
                    out.write(chunk)
            if temporary.stat().st_size != spec['bytes'] or digest(temporary) != spec['sha256']:
                raise ValueError('Downloaded preview model does not match pin')
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    if target.stat().st_size != spec['bytes'] or digest(target) != spec['sha256']:
        raise ValueError('Cached preview model does not match pin; refusing replacement')
    licenses = ROOT/'app/src/main/assets/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for name in ['vosk', 'jna']:
        source = licenses/f'{name}-license.txt'
        if source.stat().st_size < 100:
            raise ValueError('Missing preview runtime license')

    print(json.dumps({'result': 'PASS_PREVIEW_INPUTS', 'name': spec['name'], 'sha256': spec['sha256'], 'bytes': spec['bytes']}))


if __name__ == '__main__':
    main()

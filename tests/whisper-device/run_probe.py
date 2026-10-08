#!/usr/bin/env python3
"""Run production Java/JNI with public audio in an isolated Android shell directory.
Does not install/uninstall the app, access its data, start capture, or clear logs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--adb', required=True)
    p.add_argument('--server', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], required=True)
    p.add_argument('--receipt', type=Path, required=True)
    p.add_argument('--windows-temp', type=Path,
                   help='WSL path to a Windows-accessible temp directory; required with adb.exe')
    args = p.parse_args()
    windows = args.adb.lower().endswith('.exe')
    if windows and (args.windows_temp is None or not args.windows_temp.is_dir()):
        p.error('--windows-temp must name an existing Windows-accessible directory when using adb.exe')
    sdk = Path(next(line.split('=', 1)[1] for line in (ROOT/'local.properties').read_text().splitlines() if line.startswith('sdk.dir=')))
    android = sdk/'platforms/android-34/android.jar'
    tools = sdk/'build-tools/34.0.0'
    adb = [args.adb, '-P', args.server, '-s', args.serial]
    def run(command, timeout=120):
        result = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f'Command failed ({result.returncode}): {result.stdout[-2000:]} {result.stderr[-2000:]}')
        return result.stdout.strip()
    def shell(*command, timeout=120):
        return run(adb + ['shell', *command], timeout)
    properties = {key: shell('getprop', key) for key in ['ro.product.model', 'ro.product.cpu.abi', 'ro.build.version.release', 'ro.kernel.qemu']}
    if properties['ro.product.cpu.abi'] != args.abi:
        raise RuntimeError('Device ABI differs from requested artifact')
    services = shell('dumpsys', 'activity', 'services', 'app.nottheomi.ai')
    if 'CaptureService' in services:
        raise RuntimeError('Active app capture service: postpone CPU benchmark')
    remote = '/data/local/tmp/nottheomi-whisper-probe-' + uuid.uuid4().hex
    temp_root = str(args.windows_temp) if windows else None
    sources = [ROOT/'app/src/main/java/app/nottheomi/ai'/f'{name}.java' for name in ['WhisperNative','WhisperModel','WhisperRecognizer']]
    sources += [ROOT/'tests/whisper-device/WhisperDeviceProbe.java']
    model = ROOT/'app/src/main/assets/ggml-small.en-q5_1.bin'
    native = ROOT/'app/src/main/jniLibs'/args.abi/'libnottheomi-whisper.so'
    wav = ROOT/'app/src/androidTest/assets/jfk.wav'
    paths = sources + [model, native, wav]
    def digest(path):
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest()
    hashes = {str(path.relative_to(ROOT)): digest(path) for path in paths}
    receipt = {'properties': properties, 'artifact_sha256': hashes, 'scope':'production JNI and Java batching; public JFK audio; shell process, not app lifecycle'}
    with tempfile.TemporaryDirectory(prefix='nottheomi-whisper-probe-') as work:
        work = Path(work)
        classes, dex = work/'classes', work/'dex'
        classes.mkdir(); dex.mkdir()
        run(['javac','--release','17','-cp',android,'-d',classes,*sources])
        run([tools/'d8','--lib',android,'--min-api','26','--output',dex,*sorted(classes.rglob('*.class'))])
        run(['jar','cf',work/'probe.jar','-C',dex,'classes.dex'])
        with tempfile.TemporaryDirectory(prefix='nottheomi-whisper-probe-', dir=temp_root) as transfer:
            transfer = Path(transfer)
            shell('mkdir', remote)
            try:
                for path in [model, native, wav, work/'probe.jar']:
                    target = transfer/path.name
                    shutil.copyfile(path,target)
                    local = run(['wslpath','-w',target]) if windows else str(target)
                    run(adb+['push',local,remote+'/'+path.name],timeout=180)
                    got = shell('sha256sum',remote+'/'+path.name).split()[0]
                    if got != digest(path): raise RuntimeError('Device artifact checksum mismatch')
                started = time.time()
                output = shell('env','CLASSPATH='+remote+'/probe.jar','app_process',
                        '-Djava.library.path='+remote,'/system/bin','app.nottheomi.ai.WhisperDeviceProbe',
                        remote+'/'+model.name,remote+'/'+wav.name,timeout=420)
                proofs = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
                if len(proofs)!=1 or proofs[0].get('result') != 'PASS_WHISPER_DEVICE_PUBLIC_FIXTURE':
                    raise RuntimeError('Missing device inference pass')
                receipt.update(proofs[0]); receipt['probe_wall_seconds'] = time.time()-started
                receipt['real_time_factor'] = receipt['inference_seconds']/receipt['audio_seconds']
                if hashes != {str(path.relative_to(ROOT)):digest(path) for path in paths}:
                    raise RuntimeError('Inputs changed during device inference')
            finally:
                shell('rm','-rf',remote)
                if shell('test','!','-e',remote) != '': raise RuntimeError('Remote cleanup failed')
                receipt['remote_cleanup_verified'] = True
    args.receipt.parent.mkdir(parents=True,exist_ok=True)
    args.receipt.write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))

if __name__ == '__main__':
    main()

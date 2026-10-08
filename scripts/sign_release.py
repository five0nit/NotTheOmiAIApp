#!/usr/bin/env python3
"""Align/sign release APKs with a dedicated persistent key outside the repo.
Never prints passwords. Existing signing identity is never silently replaced.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def run(argv):
    result = subprocess.run([str(x) for x in argv], check=True, text=True, capture_output=True)
    return result.stdout + result.stderr

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))))
    p.add_argument('--key-dir', type=Path, required=True, help='Persistent private directory OUTSIDE repository; back it up securely')
    p.add_argument('--version', help='Must match app/build.gradle; defaults to its versionName')
    args = p.parse_args()
    match = re.search(r"versionName\s+[\"\']([0-9]+\.[0-9]+\.[0-9]+)[\"\']", (ROOT/"app/build.gradle").read_text())
    if not match or (args.version is not None and args.version != match.group(1)):
        raise SystemExit("Release version must match app/build.gradle")
    args.version = match.group(1)
    keydir = args.key_dir.expanduser().resolve()
    if keydir == ROOT or ROOT in keydir.parents:
        raise SystemExit('Signing key must remain outside repository')
    keydir.mkdir(parents=True, exist_ok=True, mode=0o700)
    keydir.chmod(0o700)
    key, password = keydir/'release.p12', keydir/'password'
    if key.exists() != password.exists():
        raise SystemExit('Incomplete signing identity; recover it. Refusing replacement.')
    if not key.exists():
        fd = os.open(password, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as out:
            out.write(secrets.token_urlsafe(36)+'\n')
        run(['keytool','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','release','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=NotTheOmiAIApp, O=Independent Android App','-storepass:file',password,'-keypass:file',password])
        key.chmod(0o600)
    tools = args.sdk/'build-tools'/'34.0.0'
    output = ROOT/'app/build/outputs/apk/release'
    metadata = json.loads((output/'output-metadata.json').read_text())
    if metadata.get('applicationId') != 'app.nottheomi.ai' or metadata.get('variantName') != 'release':
        raise SystemExit('Unexpected release output metadata')
    inputs = []
    for item in metadata.get('elements', []):
        name = item.get('outputFile', '')
        if (item.get('versionName') != args.version or Path(name).name != name
                or not re.fullmatch(r'app-[a-z0-9_-]+-release-unsigned\.apk', name)):
            raise SystemExit('Stale or invalid release output metadata; rebuild before signing')
        inputs.append(output/name)
    if not inputs or len(set(inputs)) != len(inputs) or not all(p.is_file() for p in inputs):
        raise SystemExit('No complete release APK set. Run ./gradlew assembleRelease first.')
    dest=ROOT/'dist'
    dest.mkdir(exist_ok=True)
    receipts=[]
    for src in inputs:
        abi=src.name.removeprefix('app-').removesuffix('-release-unsigned.apk')
        target=dest/f'NotTheOmiAIApp-{args.version}-{abi}.apk'
        with tempfile.TemporaryDirectory(prefix='nottheomi-sign-') as td:
            aligned=Path(td)/'aligned.apk'
            run([tools/'zipalign','-f','-p','4',src,aligned])
            run([tools/'apksigner','sign','--ks',key,'--ks-key-alias','release','--ks-pass','file:'+str(password),'--out',target,aligned])
        signature=run([tools/'apksigner','verify','--verbose','--print-certs','--min-sdk-version','26',target])
        run([tools/'zipalign','-c','-p','4',target])
        badging=run([tools/'aapt','dump','badging',target])
        perms=run([tools/'aapt','dump','permissions',target])
        assert "package: name='app.nottheomi.ai'" in badging
        assert f"versionName='{args.version}'" in badging
        assert "application-label:'NotTheOmiAIApp'" in badging
        assert "android.permission.INTERNET" not in perms
        assert 'application-debuggable' not in badging
        receipt={'file':target.name,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'signature':signature,'badging':badging,'permissions':perms,'alignment':'passed'}
        receipts.append(receipt)
        print(json.dumps({k:receipt[k] for k in ['file','bytes','sha256','alignment']}))
    (dest/'apk-verification.json').write_text(json.dumps(receipts,indent=2)+'\n')
    (dest/'SHA256SUMS.txt').write_text(''.join(r['sha256']+'  '+r['file']+'\n' for r in receipts))

if __name__=='__main__':
    main()

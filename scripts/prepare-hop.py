#!/usr/bin/env python3
"""Download and checksum-verify a clean Hop installation for E2E, not a user installation."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import zipfile
SHA512='eb227ffe3386480f5ecb59e1d9fdbb16ace15b9ebd9e02da16cdca2c3cadc869f92275b0f607952fb2c5122986839a5b09b2c5762401dad128d8de6b0394643c'
def main():
    p=argparse.ArgumentParser();p.add_argument('--destination',type=Path,required=True);a=p.parse_args()
    root=a.destination.resolve();root.mkdir(parents=True,exist_ok=True)
    if (root/'hop').exists():raise SystemExit('Destination already contains Hop; select a fresh disposable directory')
    bundle=root/'apache-hop-client-2.19.0.zip'
    if not bundle.exists():
        for base in ('https://downloads.apache.org/hop','https://archive.apache.org/dist/hop'):
            try:
                with urllib.request.urlopen(base+'/2.19.0/apache-hop-client-2.19.0.zip',timeout=60) as response,bundle.open('wb') as out:
                    import shutil
                    shutil.copyfileobj(response,out)
                break
            except Exception:
                bundle.unlink(missing_ok=True)
        if not bundle.exists():raise SystemExit('Could not download Hop')
    digest=hashlib.sha512()
    with bundle.open('rb') as f:
        for data in iter(lambda:f.read(1024*1024),b''):digest.update(data)
    if digest.hexdigest()!=SHA512:raise SystemExit('Hop SHA-512 mismatch')
    with zipfile.ZipFile(bundle) as z:
        for name in z.namelist():
            if root not in (root/name).resolve().parents:raise ValueError('Unsafe archive path')
        z.extractall(root)
    print(root/'hop')
if __name__=='__main__':main()

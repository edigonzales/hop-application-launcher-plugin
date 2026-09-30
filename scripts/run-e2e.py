#!/usr/bin/env python3
"""Test a verified ZIP in a temporary plugin tree using transforms from a Hop 2.19.0 home."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--hop-home',type=Path,required=True);p.add_argument('--plugin-zip',type=Path,required=True)
    p.add_argument('--topic-repository',type=Path);p.add_argument('--input-xml',type=Path)
    a=p.parse_args();home=a.hop_home.resolve(strict=True)
    if bool(a.topic_repository) != bool(a.input_xml):p.error('--topic-repository and --input-xml must be supplied together')
    subprocess.run(['python3','scripts/verify-package.py','--zip',str(a.plugin_zip.resolve())],cwd=ROOT,check=True)
    with tempfile.TemporaryDirectory(prefix='launcher-installed-') as tmp:
        install=Path(tmp).resolve()/'hop';install.mkdir();plugins=install/'plugins'
        with zipfile.ZipFile(a.plugin_zip) as z:
            for n in z.namelist():
                if install not in (install/n).resolve().parents:raise ValueError('ZIP path escapes installation')
            z.extractall(install)
        # Host transforms/actions are taken from the actual Hop installation. The launcher is
        # supplied exclusively by the canonical ZIP, without modifying the supplied Hop home.
        for kind,names in {'transforms':['getvariable','constant','textfile','delay'], 'actions':['pipeline']}.items():
            for name in names:shutil.copytree(home/'plugins'/kind/name,plugins/kind/name)
        cmd=['mvn','-B','-ntp']
        if os.environ.get('MAVEN_SETTINGS'):cmd+=['-s',os.environ['MAVEN_SETTINGS']]
        cmd+=['-pl','integration-tests','test','-Dlauncher.plugins='+str(plugins),'-Dlauncher.examples='+str(ROOT/'examples/hello-world')]
        if a.topic_repository:cmd+=['-Dlauncher.topic='+str(a.topic_repository.resolve(strict=True))]
        if a.input_xml:cmd+=['-Dlauncher.input='+str(a.input_xml.resolve(strict=True))]
        subprocess.run(cmd,cwd=ROOT,check=True,timeout=240)
if __name__=='__main__':main()

#!/usr/bin/env python3
"""Check the installable ZIP rather than just the Maven dependency graph."""
from pathlib import Path
import io
import zipfile
import argparse

def main():
    p=argparse.ArgumentParser();p.add_argument('--zip',type=Path);a=p.parse_args()
    files=[a.zip] if a.zip else list(Path('assemblies/target').glob('hop-application-launcher-plugin-*.zip'))
    assert len(files)==1, f'Expected one plugin ZIP: {files}'
    with zipfile.ZipFile(files[0]) as z:
        names=z.namelist();root='plugins/misc/hop-application-launcher/'
        assert all(n.startswith(root) or n.rstrip('/') in ('plugins','plugins/misc') for n in names), names
        jars=[n for n in names if n.endswith('.jar')]
        assert any('/lib/org.eclipse.jgit-' in n for n in jars)
        assert any('/lib/snakeyaml-engine-' in n for n in jars)
        assert not any('/hop-core-' in n or '/hop-engine-' in n or '/hop-ui-' in n or '/org.eclipse.swt' in n for n in jars)
        plugin=[n for n in jars if '/hop-application-launcher-' in n];assert len(plugin)==1
        with zipfile.ZipFile(io.BytesIO(z.read(plugin[0]))) as jar:
            assert 'META-INF/jandex.idx' in jar.namelist()
            assert 'ch/so/agi/hop/launcher/LauncherPerspective.class' in jar.namelist()
        assert root+'LICENSE' in names
    print('Plugin package verified:',files[0])
if __name__=='__main__':main()

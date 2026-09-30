#!/usr/bin/env python3
"""Lightweight source checks; the central checker supplies full repository/link validation."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
for path in (root/'examples').rglob('*.hpl'):ET.parse(path)
for path in (root/'examples').rglob('*.hwf'):ET.parse(path)
manual=(root/'docs/master.adoc').read_text()
for token in ('INPUT_XML','OUTPUT_DIR','launcher-local','applications.yaml','outputDirectoryParameter','[.gui-mockup]','CANCELLED'):
    assert token in manual, token
assert manual.count('\n----\n')%2==0
assert '0.75em' in (root/'docs/site.css').read_text()
print('Documentation sources and examples checked')

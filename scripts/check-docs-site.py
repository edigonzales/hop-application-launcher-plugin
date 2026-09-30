#!/usr/bin/env python3
"""Assert GUI mockups are rendered and their stylesheet is actually loaded."""
from pathlib import Path
import sys
root=Path(sys.argv[1]);pages=list(root.rglob('*.html'));assert pages,'No generated HTML'
marked=[p for p in pages if 'gui-mockup' in p.read_text()];assert marked,'GUI markup not rendered'
for page in marked:
    text=page.read_text();assert 'styles.css' in text, page
css=(root/'site-assets/styles.css').read_text()
for rule in ('.listingblock.gui-mockup pre','pre.gui-mockup','font-size: 0.75em','line-height: 1.25'):assert rule in css,rule
assert (root/'examples/hello-world/shared/hop/applications.yaml').exists()
print('Generated handbook and GUI stylesheet checked:',len(pages),'pages')

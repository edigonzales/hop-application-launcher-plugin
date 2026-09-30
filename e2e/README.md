# Installed launcher E2E

Prerequisites: JDK 21, Maven, Python 3, Git (for disposable fixtures only), and Hop 2.19.0.
Linux needs an X display (`xvfb-run -a`); macOS uses `-XstartOnFirstThread` from the shared parent.

```sh
python3 scripts/prepare-hop.py --destination .ci/hop-test
python3 scripts/run-e2e.py --hop-home .ci/hop-test/hop \
  --plugin-zip assemblies/target/hop-application-launcher-plugin-0.1.0-SNAPSHOT.zip
```

The runner installs the supplied ZIP into a temporary plugin tree and copies the required host
transforms/actions from the supplied Hop home. It tests that installed tree with Hop core/UI libraries in a separate Maven test module that has no dependency on the
launcher source module. The actual installed perspective is discovered, instantiated and operated through
SWT. Both pipelines and workflows run with transforms/actions from the Hop distribution.
Startup registers GUI plugins before perspectives and resolves the perspective with
`PluginRegistry.getClass()` before requesting its classloader, matching `HopGui.loadPerspectives()`.
This catches missing `@GuiPlugin` registration instead of accidentally initializing the loader in the test.

For local pilot acceptance add:

```sh
--topic-repository /absolute/path/to/datenportal-themenrepo \
--input-xml /absolute/path/to/20260308_Abstimmungen_ech0252.xml
```

This snapshots only the Hop catalog and pilot into a temporary Git repository, commits there and clones
from there. It never commits, pulls or pushes the source themes repository. Input XML is not copied to Git.

Results are under `integration-tests/target/installed-e2e/`: UI screenshot, pilot CSV and run reports.
The scenarios cover clone, forms, parameter forwarding, CSV replacement and quoting, cancellation,
engine failures, invalid files and update exclusion during a running application.

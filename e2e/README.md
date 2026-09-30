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

Logging checks cover native live rendering before completion, workflow child logs, error colours,
pause/resume, filtering/highlighting/case/exclusion, clearing and successive run isolation. They
check that the persisted log retains messages removed from the view and excludes unrelated Hop logs.
The output action is tested with and without sidecar output metadata, after validation and engine
failures, and when changing applications or refreshing. Closing an active view must cancel the engine,
persist its log and stop the native log timer; hiding/reactivating the view must preserve the run.

Tree checks cover multiple organizations, root-level applications, mixed pipeline/workflow icons,
manifest order, unlisted files, organization selection, stable application IDs and retained expansion
states after refresh. Removed selections fall back to the first application. Update banners and
selection locks are checked on initial load, successful refresh, empty catalogs, network failure and
explicit offline loading; the error details remain in the native log panel.

The collapsed-selection regression also emulates GTK moving its native highlight to an organization
without a user selection event. Refresh must retain the previously selected application and its
form, and late selection events from disposed items must be ignored. Explicitly selecting an
organization still clears the form and disables Start.

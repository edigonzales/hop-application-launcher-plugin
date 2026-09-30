# hop-application-launcher-plugin

A generic Apache Hop desktop perspective for running Git-managed applications with friendly parameter forms.

## Features

- Curated application tree for `.hpl` pipelines and `.hwf` workflows, grouped by organization.
- File, directory, string, choice and boolean fields; German and English UI.
- Managed Git checkout, background update status, fast-forward updates and explicit offline operation.
- Local execution, cancellation and revision-linked run reports.
- Native Hop live logs with filtering and a contextual output-directory action.

## Requirements

Apache Hop **2.19.0**, Java **21** or **25**, and a desktop SWT environment on Linux, macOS or Windows.
Application transforms/actions must be installed in Hop. Public HTTPS repositories and absolute local Git
repository paths/file URIs are supported; no Git CLI is required for the launcher.

## Install

Unzip `hop-application-launcher-plugin-0.1.0-SNAPSHOT.zip` into the Hop installation directory and restart Hop.
The ZIP installs under `plugins/misc/hop-application-launcher`. Open **Applications / Anwendungen** in the
perspective selector and configure the repository, branch and a dedicated checkout directory in **Settings**.
The default repository is `sogis/datenportal-themenrepo`, branch `main`.

## Documentation

- [Rendered handbook](https://edigonzales.github.io/hop-application-launcher-plugin/)
- [Handbook sources](docs/index.adoc), [complete manual](docs/master.adoc)
- [Runnable examples](examples/README.md), [installed E2E](e2e/README.md)
- Build documentation locally: `python3 scripts/build-docs-site.py` (optionally `--serve`).

## Build and development

Concrete prerequisites and commands are in [AGENTS.md](AGENTS.md). With JDK 21 and Maven settings prepared:

```sh
mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify
python3 scripts/verify-package.py
python3 scripts/check-docs.py
```

Run installed tests against a disposable Hop distribution with `scripts/run-e2e.py`; see the E2E guide.
The pilot is maintained in the separate themes repository. This plugin repository contains independent
examples so verification does not depend on a changing production branch.

Build and install locally (restart Hop afterwards):

```bash
./scripts/build-and-install.sh                         # /Users/stefan/Downloads/hop
./scripts/build-and-install.sh /absolute/path/to/hop   # another Hop 2.19.0 installation
```

The script runs `clean verify`, validates the ZIP, and replaces only
`plugins/misc/hop-application-launcher`, restoring the previous version if the replacement fails.
The ZIP remains in `assemblies/target/`. It requires Maven and Python 3, prefers `JAVA_HOME` when it
points to JDK 21, otherwise discovers JDK 21 through macOS or SDKMAN. Maven settings are generated
using the sibling `../hop-plugin-ci` checkout; set `HOP_CI_DIR` to use another checkout.

## Modules and artifacts

| Module | Artifact |
| --- | --- |
| `hop-application-launcher` | Perspective, catalog, repository and execution services |
| `assemblies` | `ch.so.agi:hop-application-launcher-plugin:0.1.0-SNAPSHOT:zip` |
| `integration-tests` | SWT and execution checks through the installed plugin classloader |

## CI and publication

Uses [hop-plugin-ci](https://github.com/edigonzales/hop-plugin-ci) with the `desktop-plugin` repository profile.
Workflow and helper references both track `main`. Java 21/25 are tested on Linux, macOS and Windows;
Ubuntu/Java 21 produces the canonical ZIP. Installed E2E consumes that exact artifact. Main-branch snapshot
publication to jars.interlis.guru depends on both verification and E2E. Pull requests never publish.
Biblios validates PR documentation and publishes GitHub Pages only from `main`.

## License

See [LICENSE](LICENSE).

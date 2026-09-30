# Repository instructions

Read the [shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md)
and [repository contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/plugin-repository-contract.md).
Profile: `desktop-plugin`. Shared workflows and helpers both track `main`.

## Build and tests

Use Maven, Python 3 and JDK 21; compatibility jobs also use JDK 25 on Linux/macOS/Windows.
Run from this repository root. Generate Maven settings with
`python3 "$HOP_CI_DIR/scripts/write_maven_settings.py" --output "$MAVEN_SETTINGS"`, where
`HOP_CI_DIR` points to the selected hop-plugin-ci checkout and `MAVEN_SETTINGS` to a temporary file.

- Canonical: `mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify`.
- Compatibility: `mvn -s "$MAVEN_SETTINGS" -B -ntp clean test`.
- Package: `python3 scripts/verify-package.py`.
- User-requested local installation: `./scripts/build-and-install.sh [hop-directory]` (default
  `/Users/stefan/Downloads/hop`). Build/install script tests must use a disposable target directory.
- Contract: `python3 "$HOP_CI_DIR/scripts/check-plugin-repository.py" --profile desktop-plugin`.
- Installed E2E: `python3 scripts/run-e2e.py --hop-home /absolute/disposable/hop --plugin-zip assemblies/target/hop-application-launcher-plugin-0.1.0-SNAPSHOT.zip`.
  Requires a clean Hop 2.19.0 installation; Linux needs Xvfb. Never install into the user's Hop.
- Documentation: `python3 scripts/build-docs-site.py`; `python3 scripts/check-docs.py`.
- Workflow syntax: `actionlint`; whitespace: `git diff --check`.

CI E2E uses the canonical ZIP without rebuilding it. Application source belongs in the themes
repository; examples here are independent generic test fixtures. Never commit input data or outputs.

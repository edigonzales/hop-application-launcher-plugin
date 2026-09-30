# Repository helpers

- `build-and-install.sh [hop-directory]`: build with JDK 21, verify the ZIP and install into Hop 2.19.0.
  Defaults to `/Users/stefan/Downloads/hop`; replaces this plugin's directory with rollback on failure.
  Uses `JAVA_HOME` (JDK 21) or macOS/SDKMAN discovery, plus Maven and Python 3.
  Set `HOP_CI_DIR` to override the sibling `hop-plugin-ci` checkout. Restart Hop after installation.
- `verify-package.py`: inspect an already built ZIP, including dependency and discovery-index checks.
- `prepare-hop.py`: checksum-verified disposable Apache Hop 2.19.0 installation.
- `run-e2e.py`: installed-plugin checks; never rebuilds the candidate ZIP.
- `build-docs-site.py`: build the exact checkout or `--revision` using Biblios; optionally `--serve`.
- `check-docs.py`: validate source documentation and example configuration.
- `check-docs-site.py`: verify generated GUI classes, stylesheet loading and asset presence.

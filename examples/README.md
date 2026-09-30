# Runnable examples

`hello-world/` is a complete catalog repository fixture with a pipeline and a workflow that forwards
parameters to that pipeline. Initialize a disposable copy with Git (`git init -b main`, `git add .`,
`git commit`) and select its absolute path in Launcher settings. The launcher clones it to its own checkout.

Choose a readable `.xml` file and an existing writable output directory, both outside the checkout.
The pipeline writes `hello-world.csv` (UTF-8, semicolon separator, header and one record). It includes
`Hello World` and the input path, without parsing the input. Subsequent runs replace the CSV.

The pilot in `datenportal-themenrepo/staatskanzlei/wahlresultate` uses the same pattern with its own
application ID. This example is an independent generic fixture, not a second authoritative pilot copy.

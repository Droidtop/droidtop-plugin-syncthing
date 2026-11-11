# Syncthing core (`creation`)

The core of droidtop-plugin-syncthing: the code that does the work, with no droidtop plugin glue.

## Branches

Like Enginehost's cores, this plugin is three branches:

- **`creation`** (this branch): the core code, written here.
- **`plugin-core`** (the default branch): the wrapper that makes it a droidtop plugin (the plugin host glue,
  build, signing, manifest) and the line declarations in `plugin-line/lines/<line>/line.json`, each pinning a
  `creation` commit.
- **`plugin/<line>`**: a composition, `creation` at its pin with `plugin-core` written over it, built by
  Droidtop/droidtop-platforms `tools/plugin_line.py`. Releases are built from it; nothing is authored there.

Every change lands through a `ci/` branch. There is no `main`.

## Licence

See [LICENSE](LICENSE).

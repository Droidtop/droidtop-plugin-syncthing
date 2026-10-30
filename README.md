# droidtop-plugin-syncthing

droidtop's Syncthing (`droidtop.syncthing`): a full, upstream [Syncthing](https://syncthing.net/) run by droidtop, so
two droidtop handhelds, or a handheld and droidtop-agent or Pythia on a computer, keep folders in step. It pairs
through droidtop's own device pairing, so Syncthing's pairing is never shown, and it can also sync with any other
Syncthing the person adds: a trusted one (an existing library), or an untrusted one that keeps only an encrypted copy
(a backup). Decided in Droidtop/tracker#553.

## How it fits together

- **Syncthing itself** is upstream Syncthing at the tag in `syncthing.pin`, unmodified, built as a Go `c-shared`
  library (`lib/<abi>/libsyncthing.so`, arm64-v8a and x86_64). `go/droidtopsyncthing` is this repository's own Go
  package: `build.sh` copies it into the upstream checkout as `cmd/droidtopsyncthing` and builds it there against
  upstream's `go.mod` and `go.sum`. It drives `lib/syncthing` (the library `cmd/syncthing` uses) and answers one JNI
  call, `SyncthingNative.call(op, args)`, JSON in and out.
- **The Kotlin plugin** (`src/`) is an ordinary `native_bundle` plugin. droidtop runs it in an open process with only
  the `net.sockets` hole (droidtop `docs/plugin-api.md` 5.3, "Open processes"): its own internet sockets, nothing more.
  Syncthing's configuration, database, log and folders live in the plugin's own folder, which droidtop's file broker
  maps for this process.
- **Running** is droidtop's `jobs.service` entry `syncthing` (E8). Every 30 seconds, and at once when a setting or the
  paired devices change, the service reads droidtop's networks (`net.get_links`), points Go's resolver at their DNS
  servers (the process cannot reach Android's resolver), starts or stops Syncthing by the person's options, and
  accepts the paired devices.
- **What a person sees** are facets (`ui.facets`, contract 3), drawn by droidtop with its own components in every
  mode: the plugin's page holds the status, when to sync, the folders and devices (each opening a page of its own),
  the "Add a folder" and "Add a Syncthing device" forms and Syncthing's log; the settings page holds when to sync; the
  Quick Menu shows whether it is syncing. A folder's encryption password is a `secret`: droidtop shows it on its own
  sheet, never in a message. Without `devices.paired`, the paired devices group asks for it in place. Syncthing's own
  web page is turned off: it would listen on loopback, where every app on the device could reach it.

## Pairing with droidtop's devices

The plugin needs `devices.paired` (D21) for this; without it only the devices the person adds by ID sync.

- It announces `{"syncthing": "<its device ID>", "addresses": ["tcp://<lan ip>:<port>", "quic://<lan ip>:<port>", ...]}`
  with `devices.announce` whenever that value or the set of paired devices changes. The addresses are its Wi-Fi and
  Ethernet addresses (the process cannot list interfaces itself, and Syncthing's LAN discovery falls back to
  broadcast without them), so a counterpart on the same network dials it directly.
- It reads `devices.list_paired` and accepts exactly the device IDs found under each device's `announced.syncthing`,
  named as droidtop names the device, with that device's announced addresses plus `dynamic`, trusted, and with their
  folders accepted automatically. A device that is forgotten, or stops announcing, is removed again; devices the
  person added are never touched.
- Every folder marked "Share with my paired devices" (the `droidtop` folder, and new folders unless unticked) is
  shared with all of them. The `droidtop` folder has the same ID everywhere, so paired droidtops meet in it.

droidtop-agent and Pythia's Syncthing modules announce the same shape under the same plugin id
(`droidtop.syncthing`), so they pair with a handheld the same way.

## Options

| Option | Default | |
| --- | --- | --- |
| Sync only on Wi-Fi | on | Wi-Fi or Ethernet (or a VPN over one); Syncthing is stopped on mobile data. |
| Sync only while charging | off | Read from the kernel's power supply files, since droidtop has no battery API for plugins yet (D7). Where they cannot be read, the page says the option has no effect. |
| Look for changes every | 5 minutes | The sandbox refuses inotify, so folders are rescanned instead of watched. |

Other Syncthing devices are added by ID, untrusted by default. An untrusted device gets each shared folder encrypted
with that folder's own password, made once and kept in the plugin's vault; the folder's page shows it.

## Limits of this version

- A folder elsewhere on the device (a game folder, a ROM library, saves) needs `files.shared.write`. droidtop's broker
  then opens it to Syncthing's process by path, and Syncthing syncs it in place. Without the grant, folders live in the
  plugin's own storage.
- No inotify: changes are found by the rescan interval or "Look for changes now".
- No list of devices that ask to connect: another Syncthing is added by its ID.

## Building

CI builds every push (`.github/workflows/build.yml`, droidtop-platforms' plugin bundle workflow): `prepare.sh`
installs the Go toolchain `syncthing.pin` names, `build.sh` builds both libraries and the plugin, and `sign.sh` signs
`manifest.jws` with the repository's leaf certificate in a job of its own. A `plugin-v*` tag attaches the signed bundle
to its release. `.github/workflows/syncthing-upstream.yml` checks upstream's newest release every day and opens an
issue when the pin is behind.

Upstream Syncthing is MPL-2.0; see `NOTICE`. This repository's own code is GPL-3.0-or-later.

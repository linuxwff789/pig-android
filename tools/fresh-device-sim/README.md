# Fresh-device simulator

A new phone cannot be emulated here (no root, no Android emulator), so this
splits the problem in two:

- **Termux** is real: the official bootstrap zip (`bootstrap-aarch64.zip` from
  `termux/termux-packages`) is extracted under `$SIM_ROOT/root` and run through
  `proot`. Inside there is no `pig`, no `go`, no `~/.pig` and a fresh `$HOME` —
  the same starting point as a device out of the box. The installer under test
  is the working copy (`scripts/install-termux.sh`), bound in read-only.
- **The Pig app** is a protocol client, not an Android UI: `app-sim.py` sends the
  frames the APK sends (handshake, `get_commands`, `prompt` for the provider
  buttons) and asserts what a user would see.

## Usage

```sh
tools/fresh-device-sim/sim.sh all        # wipe + bootstrap + install + relay + app checks
SIM_KEEP=1 tools/fresh-device-sim/sim.sh all  # reuse the current device (upgrade path)
tools/fresh-device-sim/sim.sh bootstrap  # just download/extract the Termux userspace
tools/fresh-device-sim/sim.sh install v1.1.32
tools/fresh-device-sim/sim.sh relay      # run it, Ctrl-C to stop
tools/fresh-device-sim/sim.sh app        # replay the app against a running relay
tools/fresh-device-sim/sim.sh pack       # bundled pack: manifest present, no leaked window
tools/fresh-device-sim/sim.sh shell      # a shell inside the simulated device
tools/fresh-device-sim/sim.sh reset      # wipe it, keep the downloaded zip
tools/fresh-device-sim/sim.sh destroy
```

Environment:

| Variable | Default | Meaning |
| --- | --- | --- |
| `SIM_ROOT` | `~/.cache/pig-fresh-device` | where the fake device lives |
| `BOOTSTRAP_TAG` | pinned release | `termux-packages` bootstrap to extract |
| `RELAY_TAG` | current tag, else GitHub latest | release the installer should install |
| `PIG_RELAY_URL` | unset (GitHub) | install this relay instead, e.g. a local build |
| `PORT` / `TOKEN` | `18793` / `sim-token` | relay the app simulation talks to |
| `SIM_KEEP` | unset | `1` keeps `$PREFIX` between `all` runs |

Testing a local relay:

```sh
cd relay && go build -trimpath -ldflags "-s -w -X main.buildVersion=1.1.32" \
  -o ~/.cache/pig-fresh-device/artifacts/pig-relay-android-arm64 .
PIG_RELAY_URL=file://$HOME/.cache/pig-fresh-device/artifacts/pig-relay-android-arm64 \
RELAY_TAG=v1.1.32 tools/fresh-device-sim/sim.sh all
```

## What the checks cover

1. handshake speaks `relay/1`;
2. the first UI pack has `manifest.json` and the theme/layout it names (the app
   refuses a pack without one - this is the "two default themes report
   manifest.json missing" bug);
3. `get_commands` lists `provider-ui`, `providers`, `providerui.save`,
   `endpoint-add` and `plan` (the embedded pigkit module really built);
4. `/provider-ui` writes `~/.pig/android-ui/windows/providers.json` and its
   `nav.json` entry, and a fresh device starts from the empty state;
5. saving a reachable provider fetches its models, writes
   `custom-endpoints.json` and the model cache, and registers it in the session;
6. saving an unreachable one is reported as a failure and says it is not in the
   session (it used to claim success while `/model` showed nothing).

## Things that are artifacts of the simulation, not bugs

- `pkg` refuses to run when proot fakes root (`-0`), so the simulator maps the
  Termux uid instead; `sudo`-style behaviour is not available.
- The relay's "a relay is running" hint can fire because `/proc` is shared with
  the host (a real device only has its own processes).
- `CANNOT LINK EXECUTABLE "/system/bin/app_process"` comes from an Android
  component that cannot start inside proot; nothing in the install path needs it.
- The first `pkg install` probes mirrors and may rotate to a different one; on a
  real device the same rotation happens, and the installer now sets
  `chosen_mirrors` so a mainland mirror is picked first.

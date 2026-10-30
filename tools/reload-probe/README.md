# ReloadProbe

A tiny test plugin for PaperProxy's runtime `/pp plugin unload|load|reload`. It shows whether a reload leaves anything behind, instead of only showing that the plugin loaded without errors.

Two jars, one per plugin type:

- `ReloadProbe-Bungee.jar` (BungeeCord plugin `ReloadProbeBungee`)
- `ReloadProbe-Velocity.jar` (Velocity plugin `reloadprobe`)

Each one has one repeating task (every 2 seconds) and one listener that logs every server switch. Every load gets a new generation number, so the log tells you which copy of the plugin is talking.

## What it reports

| Log line | Meaning |
|---|---|
| `tick generation N` | The task of the current copy runs. You should see one per 2 seconds. |
| `switch generation N player -> server` | The listener of the current copy saw a server switch. One line per switch. |
| `LEAK task of generation X runs next to generation Y` | An old copy's task still runs after a reload. |
| `LEAK task of stopped generation N still runs` | A task kept running after the plugin was unloaded. |
| `DUPLICATE listener: switch ... handled twice` | The same switch was handled twice, an old listener is still registered. |

## Manual check

1. Put both jars into `plugins/` and start the proxy.
2. Join with a player and switch servers a few times.
3. `/pp plugin unload ReloadProbeBungee confirm` and `/pp plugin unload reloadprobe confirm`. The tick lines have to stop.
4. `/pp plugin load ReloadProbe-Bungee.jar confirm` and `/pp plugin load ReloadProbe-Velocity.jar confirm`.
5. Switch servers again. Each switch has to show exactly one line per plugin and no `LEAK` or `DUPLICATE` line may appear.

## Automatic check

`reload-test.js` does the same with a bot and reads the proxy log afterwards. It needs a proxy started with a FIFO console at `<proxy-dir>/cmd`, two backends and `mineflayer`.

```bash
node reload-test.js <proxy-dir> <port> [cycles]
```

Environment: `SERVERS=lobby,factions` (the two servers to switch between), `MC_VERSION=1.21.11`, `NM=<path to node_modules/>`.

Result on PaperProxy 1.2.3-RELEASE, 3 unload/load cycles, 8 server switches:

| Platform | Fresh start | After reload |
|---|---|---|
| BungeeCord plugin | OK | OK |
| Velocity plugin | OK | OK |

The check was verified against a copy that leaks a thread on purpose: it fails with `LEAK` and `tick(s) while unloaded`.

## Build

```bash
./build.sh
```

Uses `javac` directly, the BungeeCord API from the Gradle cache and the Velocity API from a PaperProxy jar (`VELOCITY_JAR=...`).

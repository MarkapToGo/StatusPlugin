# StatusPlugin benchmark

Runs StatusPlugin on a local Paper server with 100 bots and measures what it
costs the server. It also checks that two plugin versions render exactly the
same tab list, nametags and chat.

Use it before a release that touches the tab list, chat or saving:

```sh
./gradlew build
python3 benchmark/bench.py compare path/to/previous-release.jar build/libs/StatusPlugin-<version>.jar --accept-eula
```

`compare` takes about 30 minutes and prints:

1. **Output check.** Both jars run with the default config and with a stress
   config (nametags on, legacy `&`/`§`/`&#hex` codes, a per-player rotating
   message). Every player's list name, header, footer, team, nametag prefix and
   a rendered chat message are dumped as JSON. A scripted scenario follows: a
   status change, a death change, another plugin overwriting a list name, moving
   a player to its own team, and changing a team prefix. Both files must be
   `IDENTICAL` between the jars.
2. **Performance table.** Old jar, new jar and a control run without StatusPlugin.

## Requirements

- Java 25+ (found via `STATUSBENCH_JAVA`, `JAVA_HOME` or `java_home` on macOS)
- Node.js and npm (the bots use [node-minecraft-protocol](https://github.com/PrismarineJS/node-minecraft-protocol))
- Internet access on the first run: Paper, ViaVersion/ViaBackwards and the bots' npm package are downloaded into `benchmark/work/`
- About 5 GB of free RAM and ports 25565, 25575 and 8099 free

`--accept-eula` accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the test server; it is only needed once.

## What a run does

The server is Paper 26.2 in offline mode, with a flat world and view distance 4.
The bots speak the 26.1 protocol through ViaBackwards, because
node-minecraft-protocol does not support 26.2 yet. StatusPlugin uses its
default config; the only change is that the update check is off. 80% of the
bots get a status and every bot a death count. bStats is disabled, so the test
server never shows up on the plugin's public bStats page.

| Phase | What is measured |
|---|---|
| Idle | Tick times with no players, as a reference |
| Join storm | 100 bots join, one every 50 ms; tick times and how long logins take |
| Steady state | 120 s of tick times; the tick that runs the 5 s tab refresh is reported separately. The bots count tab list, team and header packets |
| Chat delivery | 200 real chat messages; time until each bot receives each message |
| Synthetic | The harness plugin times one full tab refresh, a join event and chat rendering for all 101 viewers directly |
| `--big-data N` | Pre-fills `player-status.yml` with N players and times `setStatus` and `submitRequest` on the main thread |
| `--libertybans` | Installs LibertyBans and mutes a bot: its message must not reach other players and it must see the mute notice |
| `--jfr` | CPU profile of the tab refresh and chat; see `bench.py profile <label>` |

## Commands

```sh
# One run of one jar ("none" = control run without StatusPlugin)
python3 benchmark/bench.py run my-test build/libs/StatusPlugin-7.1.3.jar --big-data 20000 --libertybans

# Only the output check (rendered output + scenario), about 2 minutes
python3 benchmark/bench.py run my-output build/libs/StatusPlugin-7.1.3.jar --dump-only --stress

# Compare finished runs, and inspect a profile
python3 benchmark/bench.py summary old new control
python3 benchmark/bench.py profile my-test
```

Results are stored in `benchmark/work/results/<label>/`: `results.jsonl` (harness),
`bots-*.json` (bots), `dump.txt` / `scenario.txt` (rendered output), `server.log`.

## Reading the numbers

- Expect some noise. A second Java process on the machine (an IDE build,
  another test server) easily moves tick times by a few ms. Compare against the
  control run from the same session, and repeat a run if a number looks off.
- The synthetic tab refresh runs every other tick and floods the bots with
  packets, so it is more sensitive to load than the steady-state numbers.
- "Steady: refresh-tick mean" picks the slowest tick phase. The control run's
  value is the noise floor; without StatusPlugin there is no refresh.

## Files

| Path | Purpose |
|---|---|
| `bench.py` | Setup, server control over RCON, runs and reports |
| `harness/` | Small Bukkit plugin (`/bench`) that records tick times and times StatusPlugin's code via reflection, so one build works with every plugin version |
| `bots/` | Bot client with a local HTTP control endpoint (`bots.js`) |

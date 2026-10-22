<p align="center">
  <img src="assets/logo.png" alt="PaperProxy" width="600">
</p>

> **NOT AN OFFICIAL PAPER PROJECT.** PaperProxy is not affiliated with or endorsed by
> PaperMC. It is an independent fork of [Velocity](https://github.com/PaperMC/Velocity).

PaperProxy is a Minecraft proxy that runs **Velocity plugins** and **BungeeCord/Waterfall
plugins** side by side on one proxy, and fixes the things that annoy server owners about
Velocity and Waterfall today.

**Will my plugins work?** Check them with the
[PaperProxy Plugin Checker](https://lucasthcrr.github.io/PaperProxy/) (runs in your browser,
nothing is uploaded). Which plugins were tested, and how thoroughly, is listed under
[Tested Plugins](https://github.com/LucasTHCRR/PaperProxy/wiki/Tested-Plugins).

**Status: first release.** Everything below is implemented and tested on a test network,
also with real clients. It is not yet proven on large production networks, so please report
anything that breaks.

## Why PaperProxy?

Today a network has to pick one proxy and live with its gaps. Velocity is fast and secure, but
it cannot run the large library of BungeeCord plugins. Waterfall runs them, but it is end of
life and gets no more updates. Many networks keep an old proxy alive only for one or two
plugins they cannot replace.

PaperProxy removes that choice. It is Velocity, with a BungeeCord layer on top, so you keep
Velocity's speed and security and still use the plugins you already have.

## Why use it?

- **Keep your plugins.** Velocity and BungeeCord plugins in the same `plugins/` folder, no
  second proxy, no rewrite.
- **Move without risk.** Drop the jar in, keep your `velocity.toml`. Going back to Velocity is
  the same step in reverse.
- **Secure forwarding for every backend.** Modern forwarding where possible, and PaperGuard
  (signed, replay-proof) for old servers that only speak BungeeCord forwarding, set per
  server.
- **Less restarting.** Reload, load and unload plugins at runtime, change messages and MOTDs
  with `/pp reload`.
- **Things you would otherwise install plugins for:** maintenance mode, health checks, ping
  cache, `/alert`, `/find`, ViaVersion install and per-server version rules.
- **Small and safe updates.** A 2 MB jar that checks every library it downloads, update
  notices, and an optional auto-updater that only installs signed releases.
- **Check first.** The [Plugin Checker](https://lucasthcrr.github.io/PaperProxy/) tells you
  before you switch which of your plugins will work.

## Features

### BungeeCord plugins on Velocity

Drop BungeeCord plugins into `plugins/` next to your Velocity plugins. They run on the original
BungeeCord API, implemented on top of Velocity:

- Plugins load through BungeeCord's own plugin manager (dependencies, `libraries`, lifecycle)
- Players, servers, commands with tab completion, permissions (LuckPerms works), titles,
  action bars, tab list header, cookies, server links, scheduler, YAML/JSON config
- Login, ping, connect, kick, chat, command, plugin message and settings events, including
  async intents
- `getServers().put(...)` registers servers like on BungeeCord
- The layer runs in its own class loader and only starts when a BungeeCord plugin is present;
  Velocity plugins never see BungeeCord classes

- `unsafe().sendPacket` works: scoreboards, teams, tab list entries and boss bars sent by
  scoreboard and tab plugins are encoded for the player's version (up to 1.21.7, the newest
  version the BungeeCord protocol classes know)

Not supported: plugins that hook into the Netty pipeline or read packets, reading the backend
scoreboard through `getScoreboard()`, and dialogs. Such calls log a clear message.

### Forwarding per server and PaperGuard

Each backend can use its own forwarding mode in `paperproxy.toml`:

```toml
[forwarding.servers]
lobby = "modern"
old-pvp = "paperguard"
```

**PaperGuard** is signed, replay-proof forwarding for servers that cannot use modern forwarding.
Every login is signed with HMAC-SHA256 over all player data, the target server name, a timestamp
and a one-time nonce, using a key derived per server. The backend plugin
[**PaperGuard**](https://github.com/LucasTHCRR/PaperGuard) (Paper 1.12.2+, Folia) verifies it and **rejects everyone when it is not configured** (fail
closed). Replayed, expired, modified or foreign logins are rejected; this is covered by attack
tests and shared test vectors. PaperGuard has its own repository with the protocol spec, so
other proxies can support it too. It was called PaperProxy-Bridge before.

Setup: set the server to `paperguard`, run `paperproxy paperguard key <server>` in the proxy
console, put server name and key into `plugins/PaperGuard/config.yml` on the backend and
set `settings.bungeecord: true` in its `spigot.yml`.

### Everything else

- **messages.yml**: every proxy message editable, MiniMessage and `&` codes, `{placeholders}`,
  optional per-client-language files; new keys are merged in after updates, your texts and own
  sections are kept
- **motd.yml**: rotating or random MOTDs, hover lines, text instead of the player count
- **Plugin reload/load/unload** for Velocity and BungeeCord plugins, with confirmation, block
  list and leak detection
- **Watchdog** that names plugins blocking events
- **ViaVersion**: `paperproxy via install|update` from Modrinth, allowed client versions per
  server, warning when Via runs on proxy and backend
- **Server groups**: `lobby = ["lobby-1", "lobby-2"]` sends players to the emptiest member and
  skips full or offline ones
- **Queues**: full or offline servers get a queue with position in the action bar and priority
  permissions; players moved away by a server restart are sent back once it is online again
- **`/hub`** (and `/lobby`) for a server or group of your choice
- **Bot protection**: new players must ping the server list before they join (during attacks or
  always), connection floods switch on attack mode in which only known players can
  join; optional limit of accounts per IP and blocked name patterns
- **Network sync** over Redis for several proxies: network wide player count, `/find` and
  `/alert` across proxies, no double logins, `/pp network`
- **Prometheus metrics** at `/metrics` (players, servers, queues, logins, connect times, kicks,
  client versions, memory), off by default, with a ready
  [Grafana dashboard](docs/grafana/paperproxy-dashboard.json)
- **Restart without kicks**: `shutdown.transfer-to` hands players to another proxy (1.20.5+)
- **Update folder**: put new jars into `plugins/update`, they replace the old version (same file
  name or same plugin id) on the next start
- **Hidden commands**: keep commands like `/server` or `/plugins` out of tab completion
- **Query passthrough**: server queries show the map, version and plugins of a backend
- **Limbo**: when a server restarts or crashes and no other server can take its players, they
  stay connected and are sent back as soon as it is online again, no plugin or extra server
- **Parties**: `/party` and `/pc`, members follow their leader from server to server
- **Bans, mutes and kicks** for the whole network (opt-in), shared across proxies
- **Planned restarts** with countdown, titles and transfer to another proxy
- **Discord webhook** for servers going down, bot attacks, maintenance, updates, bans
- **`/send` for groups and servers**: `/send all lobby`, `/send server game-1 lobby`
- **Health checks**: offline servers are skipped and refused with a message, staff is notified
- **Maintenance** for the whole network or single servers, with whitelist
- **Ping cache** against server list floods
- `/alert`, `/find`, `/ip`
- **Update notifications** from GitHub releases and an optional, signature-verified
  auto-updater (off by default)
- **API** for plugins (MIT licensed): `PaperProxy.get()`

## Commands

| Command | Permission |
|---|---|
| `/paperproxy` (`/pp`) | everyone, unless `paperproxy.command.info` is denied |
| `/pp reload` | `paperproxy.command.reload` |
| `/pp servers` | `paperproxy.command.servers` |
| `/pp maintenance [on\|off] [server]` | `paperproxy.command.maintenance` |
| `/pp plugin list\|reload\|unload\|load` | `paperproxy.command.plugin` |
| `/pp via install\|update` | `paperproxy.command.via` |
| `/pp update` | `paperproxy.update` |
| `pp paperguard key\|rotate` | console only |
| `/pp network` | `paperproxy.command.servers` |
| `/alert`, `/find`, `/ip` | `paperproxy.command.alert`, `.find`, `.ip` |
| `/hub`, `/lobby` | everyone (when `hub-command.target` is set) |
| `/queue`, `/queue leave` | everyone |
| `/party`, `/pc` | everyone |
| `/ban`, `/unban`, `/banlist`, `/mute`, `/unmute`, `/kick` | `paperproxy.command.ban`, `.mute`, `.kick` (only with `punish.enabled`) |
| `/paperproxy restart <seconds> [reason]`, `restart cancel` | `paperproxy.command.restart` |

Other permissions: `paperproxy.maintenance.bypass`, `paperproxy.notify.health`,
`paperproxy.update` (update notice on join), `paperproxy.queue.bypass` (join full servers),
`paperproxy.queue.priority.<0-100>`, `paperproxy.antibot.bypass` (accounts per IP limit),
`paperproxy.tabcomplete.bypass` (sees hidden commands), `paperproxy.punish.exempt`,
`paperproxy.notify.antibot`.

## API

The API is the normal Velocity API plus the PaperProxy extras, MIT licensed. Plugins that use
it need Java 25 like current Velocity.

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Velocity's Brigadier
    maven("https://codeberg.org/api/packages/LucasTHCR/maven")
}

dependencies {
    compileOnly("net.paperstream:paperproxy-api:1.2.3-RELEASE")
    annotationProcessor("net.paperstream:paperproxy-api:1.2.3-RELEASE")
}
```

Maven:

```xml
<repositories>
  <repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
  </repository>
  <repository>
    <id>paperproxy</id>
    <url>https://codeberg.org/api/packages/LucasTHCR/maven</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>net.paperstream</groupId>
    <artifactId>paperproxy-api</artifactId>
    <version>1.2.3-RELEASE</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

To support plain Velocity as well, only touch the PaperProxy classes when they exist:

```java
boolean paperProxy;
try {
  Class.forName("net.paperstream.paperproxy.api.PaperProxy");
  paperProxy = true;
} catch (ClassNotFoundException e) {
  paperProxy = false;
}
```

```java
PaperProxy pp = PaperProxy.get();
pp.getClientVersion(player);                  // "1.21.4"
pp.getMessage("myplugin.welcome", "player", name);
pp.isOnline("survival");
pp.async(() -> database.save(player));

// Parties
PartyManager parties = pp.getParties();
Party party = parties.create(leader);
parties.addMember(party, player);
parties.getParty(player).ifPresent(p -> p.sendMessage(text));
```

Events: `BackendHealthChangeEvent`, `ClientVersionDeniedEvent`, `PluginRuntimeChangeEvent`,
`PartyJoinEvent`, `PartyLeaveEvent` (with the reason) and `PartyFollowEvent`, which can be
cancelled to keep a member on their server, for example during a minigame round.
Everything else is the normal Velocity API.

## Building

Requires Java 25 (same as current Velocity).

```bash
./gradlew build
```

- Proxy: `proxy/build/libs/paperproxy-<version>.jar` (about 2 MB). On first start it downloads
  its libraries into `libraries/` and checks every file against its SHA-256.
- Offline variant with everything inside: `proxy/build/libs/paperproxy-<version>-full.jar`
- Backend plugin: see [PaperGuard](https://github.com/LucasTHCRR/PaperGuard)

## Pterodactyl

Import [`pterodactyl/egg-paperproxy.json`](pterodactyl/egg-paperproxy.json) in your panel
(Admin, Nests, Import Egg). It installs the latest release (or a chosen tag), checks the
SHA-512 checksum and can use the offline `-full` jar.

## Metrics

PaperProxy reports anonymous usage statistics to
[bStats](https://bstats.org/plugin/server-implementation/PaperProxy/34471).
They can be disabled in `plugins/bStats/config.txt`.

## Community

Questions and feedback: [Discord](https://dc.gg/paperstream)

Mirror: [Codeberg](https://codeberg.org/LucasTHCR/PaperProxy)

## Credits and license

PaperProxy is based on [Velocity](https://github.com/PaperMC/Velocity) by the Velocity
contributors and PaperMC. "Paper" and "Velocity" are names of PaperMC projects; PaperProxy
is not one of them. The BungeeCord compatibility layer uses the BungeeCord API by md_5
(BSD license).

PaperProxy is licensed under the [GNU General Public License v3.0](LICENSE), like Velocity.
The API (`api/`) is MIT licensed.

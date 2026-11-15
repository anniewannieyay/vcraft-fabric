# VCraft

Voice calls, group chats and direct messages for Minecraft (Fabric). It works anywhere in
the game: the title screen, singleplayer, or while you and your friends are on different
servers. Everything is end-to-end encrypted.

**One jar supports Minecraft 1.21.11 and 26.3.** Install it with the matching Fabric API.

Download: [Modrinth](https://modrinth.com/mod/vcraftimac). Licence: MIT. There's also a
native port for the Horizon client: [vcraft-horizon](https://github.com/anniewannieyay/vcraft-horizon).

> **Which version is this?** The Gradle project here is VCraft 2.3.0. Releases 2.4.0 to 2.5.1
> were built by swapping recompiled classes into the previous jar; their sources and build
> scripts are in [`releases/`](releases/README.md). The relay in `relay/` is relay 2.3.1
> (safer sign-in, with a nonce in each challenge).

## Features

- **Friends:** add by Minecraft username, accept or decline requests, remove friends, block
  and unblock.
- **Status:** Online, Idle, Do Not Disturb (no pop-ups, sounds or ringing) and Offline
  (you appear offline).
- **Direct messages and group chats** (up to 16 people), with history, unread badges, replies,
  deleting your own messages, and offline delivery.
- **Server invites:** send an invite to the server you're on from any chat, and your friend
  joins with one click.
- **Voice calls:** call a friend or a whole group, and invite more people into a call that's
  already running. Mute (`M`), deafen (`N`), push-to-talk (`V`) or voice activation, a mic
  test, and per-person mute.
- **Notifications:** a ding when you're messaged, and a **music disc ringtone** for incoming
  calls. Pick any disc, including discs added by mods or resource packs.
- **In-game overlay** showing who's in the call and who's talking.
- **VCraft Community:** an official public chat everyone on the relay can join. It has slow mode and moderation, and it is clearly marked as not end-to-end encrypted.
- **Ranks:** an Owner or Mod badge shows next to staff names everywhere.
- **Skin profile pictures:** every avatar is the player's own Minecraft skin face.
- **Privacy controls:** *Download my data* and *Delete account* in Settings.

Open VCraft with **O**, or the **VCraft** button at the top left of the title screen and pause
menu. Commands: `/vc msg | call | invite | accept | decline | hangup | mute | deafen | status | add`.

## How it works

Players connect to a small **relay** (`vcraft-relay.jar`) instead of directly to each other,
because NAT and firewalls make direct connections unreliable. One person or a cheap VPS runs
it, and everyone puts its address in VCraft's settings.

- **Sign-in:** the relay verifies your Minecraft account with Mojang, the same way a server
  does. Your access token never leaves your PC.
- **Encryption:** each account has an X25519 key pair. DMs are sealed with AES-256-GCM using a
  key shared between the two people. Group messages are sealed separately for each member.
  Replies and server invites travel inside the encrypted part.
- **Voice:** every caller encrypts their audio with their own random key and gives it only to
  the other members. Keys rotate when someone leaves.
- **Key changes:** keys are pinned the first time they're seen. If one changes, VCraft stops
  encrypting to that person until you compare **safety numbers** and press Trust.
- **What the relay can see:** who your friends and groups are, when you're online, who calls
- **The community chat** is the exception: it's public and not end-to-end encrypted.
  whom, and message sizes and timing. It can't see message text or hear voice.

Back up `config/vcraft/identity-*.json`. It holds your private key, so keep it private.

## Building

Requires **JDK 25** to build. The 1.21.11 part is compiled for Java 21.

```bash
./gradlew build
```

- Mod: `build/libs/vcraft-2.3.0.jar`
- Addons: `build/libs/addons/`
- Relay: `relay/build/libs/vcraft-relay-2.3.0.jar`

## Running the relay

```bash
java -jar vcraft-relay-2.1.0.jar --port 24470
```

| Option | Env var | Default |
|---|---|---|
| `--port` | `VCRAFT_PORT` | 24470 |
| `--bind` | `VCRAFT_BIND` | 0.0.0.0 |
| `--data` | `VCRAFT_DATA` | `vcraft-data` |
| `--trust-proxy` | `VCRAFT_TRUST_PROXY` | off. Read client IPs from `CF-Connecting-IP` / `X-Forwarded-For`; only use this behind a proxy. |
| `--max-connections` | `VCRAFT_MAX_CONNECTIONS` | 5000 |
| `--max-per-ip` | `VCRAFT_MAX_PER_IP` | 8 |
| `--auth-timeout` | `VCRAFT_AUTH_TIMEOUT` | 20 seconds to sign in |
| `--retention-days` | `VCRAFT_RETENTION_DAYS` | 365 (0 keeps messages forever) |
| `--no-console` | | Don't read admin commands from stdin |
| `--offline` | `VCRAFT_OFFLINE=true` | off. Skips Mojang verification; local testing only. |

### Admin console

Type these into the relay's terminal:

| Command | |
|---|---|
| `stats`, `list` | Overview, and who's online |
| `kick <name>`, `ban <name>`, `unban <name>` | Remove someone, or keep them out |
| `rank <name> owner\|mod\|none` | Ranks show as a badge next to the name; owners and mods can moderate the community chat in-game |
| `mute <name>`, `unmute <name>`, `clear <name>` | Community chat moderation |
| `delete <name>` | Permanently delete someone's data (data-deletion requests) |

dufeaver is the default owner of every new relay.

### Built-in protections

- **Connections:** at most 8 per IP and 5000 in total, and clients must sign in within 20 seconds.
- **Size limits:** messages over 128 KB are refused, and voice packets over 1 KB are dropped.
- **Rate limits** on:
  - sign-ins (10 per minute per IP)
  - friend requests
  - Mojang name lookups (a shared budget, so one user can't get the relay rate-limited)
  - group changes, call invites, history requests and messages
  - voice packets
  - community chat (slow mode)
- **Caps:** up to 500 friends, 100 outgoing requests and 30 groups per person, 16 people per group, 300 messages kept per conversation and 500 in the community chat.
- **Cleanup:** messages older than the retention period are deleted hourly, and placeholder accounts nobody uses are pruned.

## Hosting a public relay

1. **Server:** get a small Linux VPS with Java 21+.
2. **Run the relay:** install it as a service with [deploy/vcraft-relay.service](deploy/vcraft-relay.service), which runs it as an unprivileged user, restarts it automatically and binds it to localhost.
3. **Add TLS:** use [deploy/Caddyfile](deploy/Caddyfile), or Cloudflare Tunnel. Players then connect with `wss://relay.example.com`.
4. **Hide the server's IP (optional):** put the domain behind Cloudflare's proxy, which supports WebSockets. Keep `--trust-proxy` on, so per-IP limits see players' real IPs.
5. **Make it the default:** set `serverUrl` in `common/src/main/java/dev/vcraft/core/Config.java` to your relay, so new players connect with no setup, then rebuild.
6. **Privacy policy:** fill in [PRIVACY.md](PRIVACY.md) and link it from your mod page.
7. **Backups:** back up `/opt/vcraft/data`.

## Publishing updates

VCraft updates itself. After connecting, it checks `https://<relay host>/vcraft/update.json`, downloads a newer
release, and installs it when Minecraft closes. Releases must be **signed** with your release key
(`~/.vcraft/release-key.json`). The mod only trusts the public key built into it, so a hacked relay or web
server can't push code to players.

1. Bump `mod_version` in `gradle.properties`, then run `./gradlew build`.
2. Run `deploy/publish-update.sh <server-ip>`.

Keep the release key private and **back it up**. Without it you can't publish auto-updates; players would
have to install a mod built with a new key by hand. Players can turn updates off in Settings.

## Addons

Three optional addons, each its own mod jar. They need VCraft 2.3.0 or newer and a relay running 2.3.0 or
newer. Drop them in `mods/` next to VCraft. They only use VCraft's addon API, with no Minecraft classes, so
each jar works on both 1.21.11 and 26.3. VCraft works exactly the same when none are installed.

`./gradlew build` puts them in `build/libs/addons/`.

### Proximity Chat (`vcraft-proximity`)

Hear VCraft players on the same Minecraft server without a call. Voices get quieter with in-game distance:
full volume within 6 blocks, then fading to silence at your range (48 blocks by default, 8 to 128). It's
**off by default**; turn it on in VCraft → Proximity Chat.

- **How it finds people.** Each player's game sends the relay a hash of the server address, and everyone with
  the same hash shares a voice room (up to 32 players). Volume is worked out on your own computer from the
  player positions your game already knows, so the Minecraft server doesn't need a mod.
- **Privacy.** While it's on, the relay knows which server you're on. The address is hashed, but anyone who
  knows an address can compute its hash. The page says this before you turn it on.
- **Encryption.** Voice is end-to-end encrypted with the same per-speaker keys as calls. Keys rotate whenever
  someone joins or leaves the room.
- **Controls.** Mute, deafen and push-to-talk work as usual, and blocked players can't hear each other.
  Proximity pauses while you're in a normal call. Players talking nearby show on the HUD.
- **Speaker icon.** A small speaker icon shows above the nametag of anyone nearby who's talking to you, like
  Simple Voice Chat's. It follows vanilla nametags: hidden for invisible or sneaking players. It's also hidden
  while you're deafened or in a call, and for players you've muted or blocked. You can turn it off on the
  Proximity Chat page.

### Announcements (`vcraft-announcements`)

Event announcements from the VCraft owner. Each one can have a title, a description, a date and time, and an
invite to the server the owner is on, and players can RSVP. New announcements pop up as a toast, which
players can turn off.

- **Who can post.** Only the **Owner** rank, and the relay checks this itself.
- **Public and signed.** Announcements are not end-to-end encrypted, and the page says so. The owner's
  VCraft signs each one with a signing key that stays on their computer
  (`config/vcraft/signing-<account>.json`). The relay checks the signature, and every player's game checks it
  too and remembers the owner's key. If the relay altered an announcement, it would show as invalid.
- **Moving computers.** If the owner moves to a new computer, players see a "signed with a different key"
  warning until they choose "Trust new key". Copy the signing file to the new computer to avoid this.

### Group Rules (`vcraft-grouprules`)

A rules button in each group chat. The group owner can set:

- filtered words (hide the message, mask the words with ***, or block sending),
- slow mode,
- admins-only posting, and group admins,
- a rules text members must accept before they can post.

Owners and admins can also mute and kick members. Admins can't act on the owner or on other admins. Groups
still hold up to 16 people.

Group messages are end-to-end encrypted, so the relay can't see their content.

- **Content rules are enforced by clients.** The owner's VCraft signs the filtered words and rules text, and
  seals a copy to each member. The sender's VCraft refuses to send a message that breaks a rule, and every
  receiver's VCraft hides or masks it as well, so a modified client can't get around it for members who have
  the addon.
- **Late members.** Someone who joins later gets a copy re-sealed by another member. The owner's signature
  means that member can't change it.
- **Relay-enforced rules.** Rules that don't depend on content are enforced by the relay for everyone:
  slow mode, admins-only posting, mutes, kicks and accepting the rules.
- **Limits.** Members without the addon don't filter what they see, and can't read the rules, so in a group
  that requires accepting them they can't post.

### Writing an addon

Implement `dev.vcraft.api.VCraftAddon` and list it under a `"vcraft"` entrypoint in your `fabric.mod.json`
(see `addons/*/src/main/resources/fabric.mod.json`). `VCraftApi` covers:

- **Events.** Relay connected, message received, call joined or left, someone started or stopped talking,
  and each tick.
- **Relay messages.** Your own message types, which must contain a dot.
- **Screen.** Pages, chat-header buttons, HUD lines and message filters, drawn through a version-neutral
  `Canvas`.
- **Voice.** Voice rooms with per-speaker volume.
- **Data and crypto.** Read-only game info, end-to-end sealing to one player, and a per-player Ed25519
  signing key.

The relay only understands the addon message types it has handlers for; see `relay/.../RelayAddons.java`.

## Project layout

```
common/            Version-independent client code: relay connection, state, crypto, audio
mc/                Minecraft-facing code shared by all versions (UI, HUD, commands, platform)
versions/1.21.11/  Builds mc/ against 1.21.11 (remapped to intermediary)
versions/26.3/     Builds mc/ against 26.3 (unobfuscated)
relay/             Standalone relay server
addons/            Optional addon mods (proximity, announcements, grouprules)
bot/               Test bot
```

Code in `mc/` can contain `//#if v26` / `//#else` / `//#endif` blocks for the few APIs that
differ between versions (see `gradle/mc-version.gradle`). Each version's classes go into their
own package (`dev.vcraft.mc.v1_21_11`, `dev.vcraft.mc.v26_3`), and `VCraftEntry` loads the one
for the running game.

## Notes

- **macOS:** the first call asks for microphone access for Java or the launcher.
- **Audio:** 16 kHz mono speech with IMA ADPCM, about 80 kbit/s per speaker. On a poor
  connection it adds delay rather than dropping out.
- **Keybinds:** the default `V`/`M`/`N` keys may clash with other voice mods. Rebind them under
  Options → Controls → VCraft.

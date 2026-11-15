# Releases after 2.3.0

The Gradle project at the top of this repository is VCraft **2.3.0**, the last
version built with `./gradlew build`. Versions 2.4.0 to 2.5.1 were built a
different way: by recompiling only the classes that changed and swapping them
into a copy of the previous release's jar. Every other file in the jar stays
byte for byte the same.

Each folder here holds exactly what that release changed, plus the script
that built it:

| Folder | Built from | What changed |
|---|---|---|
| `2.4.0/` | `vcraft-2.3.0.jar` | Microphone noise suppression: `Denoiser` (new), `VoiceEngine`, `Config` |
| `2.5.0/` | `vcraft-2.4.0.jar` | Calls, friends, groups, HUD, languages and the debug log: the core classes in `src/dev/vcraft/core` and each game version's `mc` package |
| `2.5.1/` | `vcraft-2.5.0.jar` | Safer sign-in: `RelayConnection` computes the Mojang session id itself |

Some of the files in `2.4.0/` and `2.5.0/` started as decompiled classes, so
they use decompiler names such as `var3` in places.

## 2.5.1: safer sign-in

The relay's challenge now carries a `nonce`. VCraft computes the Mojang
session id from it and the relay's host:

```
serverId = minecraftHexDigest(SHA-1("vcraft-relay-v1|" + lowercase host + "|" + nonce))
```

and never uses an id the relay picks, so a relay can't pass on the hash of a
handshake with a real online-mode server. A relay that sends no nonce isn't
signed in to: VCraft disconnects and says why. The relay side is in
`relay/src/main/java/dev/vcraft/relay/SessionId.java` (relay 2.3.1). The same
protocol is used by [VCraft for Horizon](https://github.com/anniewannieyay/vcraft-horizon),
documented in its `docs/relay-auth.md`.

Build it on Windows (JDK 21 and Prism's libraries, as for 2.5.0):

```powershell
powershell -File releases\2.5.1\build.ps1 -Base path\to\vcraft-2.5.0.jar
```

This compiles `RelayConnection` and `VCraft` against the 2.5.0 jar and runs
`Repack.java`, which swaps the three classes in and bumps the version in
`fabric.mod.json` and the manifest.

## Folding this back into Gradle

To build everything with Gradle again, copy each release's files over the
matching paths in `common/` and `mc/` (2.5.0 splits `mc` into `v1_21_11`
and `v26_3`, like the jar does) and bump `mod_version`.

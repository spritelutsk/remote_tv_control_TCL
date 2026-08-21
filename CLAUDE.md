# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

An Android phone app that controls an Android TV over the Remote v2 protocol — the same channel
the stock Google TV remote uses. The protocol is implemented from scratch in Kotlin (pairing on
port 6467, remote session on port 6466, protobuf messages over TLS); the UI is Jetpack Compose.

Verified against a TCL BeyondTV (firmware 6.9.906821247).

## Common Commands

```powershell
.\gradlew.bat :app:assembleRelease   # dist\app-release.apk (~3 MB), debug-signed so it installs
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:test              # unit tests
```

Live diagnostics against a real TV — skipped unless `-PtvHost` is given:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LiveTvProbe*' -PtvHost=192.168.1.106
```

Probes that connect as an already-paired client also need `-PtvCert=<dir with cert.pem/key.pem>`;
`probeVoice` optionally takes `-PtvVoice=<8 kHz mono WAV>`.

## Architecture Notes

- `tv/` holds the protocol: `CertStore` (client certificate + TLS context), `MessageStream`
  (varint-length framing), `Pairing` + `PairingSecret`, `RemoteSession` (commands, ping, state,
  voice), `TvController` (one live session, reconnect, PIN requests), `TvDiscovery` (mDNS),
  `SpeechToText` (on-phone recognition).
- `ui/` is the Compose remote; `RemoteViewModel` glues it to `TvController`.
- `proto/` contains Java classes generated once from the `.proto` schemas — the protobuf Gradle
  plugin is not used, because `protoc` cannot open files under a Cyrillic path.

Three findings that cost real debugging time are written up in `README.md` under «Ключевые
решения» — read them before touching pairing or voice:

1. The client certificate must be forced onto every handshake (the TV names no known CAs).
2. Over TLS 1.3 a rejected certificate surfaces as an error on the first read, not during the
   handshake — so any disconnect before the first message means «pair again».
3. The TV accepts a voice stream but does not recognise it; speech is recognised on the phone
   and sent as a search link instead.

## Build Environment

- The project path contains Cyrillic characters. `android.overridePathCheck=true` silences AGP,
  and the build directory is redirected to `%TEMP%\tv-remote-build` — otherwise the Gradle test
  worker cannot load classes. Built APKs are copied back into `dist/` by the `exportApk` task.
- `local.properties` (SDK path) is machine-specific and not tracked.

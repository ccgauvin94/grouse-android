# Grouse

A native Android client for a self-hosted [goose](https://github.com/block/goose), spoken to
over ACP.

> **Alpha.** It works and is used daily, but it is one person's app against an explicitly
> unstable protocol. Expect rough edges, and expect goose updates to occasionally break things.

## What it is

The phone is a thin client. The goose server holds everything that matters — sessions, memory,
extensions, model choice, recipes, schedules — and this is an ACP client plus a chat UI over
it. Almost nothing is kept on the phone that the server does not already own.

Other clients see the same state: a chat started here shows up in Goose Desktop and the CLI,
and the other way round.

- Chat with streaming, tool calls and images
- Session list, rename, archive, projects
- Extension and tool management
- Model/provider picker
- Recipes and schedules
- Quick Settings tile, share target, notification replies
- Optional biometric lock

## Requirements

A reachable `goose serve` with a secret key:

```sh
goose serve --host 0.0.0.0 --port 3284
```

Set `GOOSE_SERVER__SECRET_KEY` in its environment — that is the key the app asks for. The app
sends it as `X-Secret-Key` on the WebSocket upgrade (or `?token=` where a header cannot be set).

**Expose it carefully.** goose is an agent with shell access: anything that can reach this
endpoint can run commands as the user running goose. A VPN or overlay network is the sane
default. If it must be on the internet, put real authentication in front of it — the secret key
alone is one shared credential.

For TLS, `goose serve` also takes `--tls --tls-cert-path … --tls-key-path …`. With a
self-signed certificate the app pins the fingerprint on first connect.

## Building

JDK 17 and the Android SDK.

```sh
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## First run

The app asks for host, port, secret key, and a **working directory** — an absolute path *on the
server*. goose validates that `session/new`'s cwd is absolute and has no default of its own, so
it has to be supplied.

The phone shares no filesystem with the server: a path typed here is a claim about a machine
the app cannot see.

## Assistant mode

Off by default. It surfaces one persistent thread kept fed by scheduled recipes that run
server-side — a setup a particular server has, not something a stock `goose serve` provides.
With it off this is a plain chat client. Settings › Assistant turns it on.

## Remote sessions (roam)

This branch adds support for goose's roam federation: sessions living on other machines
appear in the drawer under a per-peer REMOTE section, marked with a globe. The phone still
holds exactly one connection — the home server's `goose serve` recognises `roam:<peer>:<id>`
session ids and forwards those calls over an iroh bridge to the peer's `goose roam share`.
Chatting, the model/mode picker, tool management, rename, archive and delete all work on a
remote session; the server refuses anything it can't forward soundly.

Needs a server built from the `feat/acp-federate-roam` branch of
[ccgauvin94/goose](https://github.com/ccgauvin94/goose) with `GOOSE_ROAM_FEDERATE=<peers>`
set. A stock server ignores all of this and the app behaves as plain Grouse.

![How the roam connection works](docs/roam-connection.svg)

## Compatibility

Everything here speaks stock ACP: no server-side patches, no forked goose methods.

goose's ACP surface is explicitly unstable — the methods live under `_goose/unstable/` — so a
goose upgrade can change or remove things this app calls. Please open an issue if something
breaks.

## Licence

AGPL-3.0. See [LICENSE](LICENSE).

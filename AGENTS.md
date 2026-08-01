# AGENTS.md

Grouse — a native Android client for a self-hosted goose (`goose serve`), spoken to over ACP.

This file is read automatically by goose from a session's working directory up to the git root,
so anything here is context for every session opened in this repo. Keep it to things that are
true and non-obvious; the code says the rest.

## What this is, and what it deliberately is not

The phone is a thin client. The server holds every piece of state that matters — sessions,
memory, extensions, model choice, recipes, schedules — and this app is an ACP client plus a chat
UI over it. When something looks like it needs local state, check first whether the server
already has it: several bugs here have been the app keeping its own copy of something the server
owns and the two disagreeing.

Two consequences worth internalising:

- **The phone shares no filesystem with the server.** A path that exists here does not exist
  there, and vice versa. Anything that needs a server path (browsing directories, opening a
  repo) has to ask the server for it — see `fs/list_directory`, which is bounded by
  `GOOSE_BROWSE_ROOTS` on the server side.
- **Other clients exist.** Goose Desktop and the CLI talk to the same server and change the same
  sessions. Anything cached here can be made stale by a client this app cannot see.

## Build

```sh
source env.sh          # JDK 17 + Android SDK paths; there is no emulator on this box
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. `:app:compileDebugKotlin` is the fast check.
`./gradlew --stop` before moving or renaming the checkout — the transform cache records absolute
paths and a moved tree confuses it.

`env.sh` hardcodes one JDK install path. If gradle dies with `JAVA_HOME is set to an invalid
directory`, the JDK that path names is simply not on that machine — fix `JAVA_HOME` in `env.sh`
(and install a JDK 17 if need be) before anything else.

## Layout

| File | What lives there |
|---|---|
| `AcpClient.kt` | The wire: JSON-RPC over WebSocket, one `AcpEvent` per server message, parsers |
| `ConnectionManager.kt` | Process-scoped singleton owning the connection and all chat state |
| `Screens.kt` | Every Compose screen. Large, and deliberately not split by feature |
| `MainActivity.kt` | Drawer, nav graph, lifecycle, the biometric lock |
| `SecureStore.kt` | Preferences; the secret key lives in Keystore-backed encrypted prefs |

## Things that have bitten, and will again

**goose's ACP surface mixes camelCase and snake_case, and reads the wrong one as null.** Most
methods are camelCase; `recipes/list` returns `file_path` and `schedule_cron`, and
`recipes/schedule` takes `cron_schedule`. A wrong spelling does not error — the field is simply
absent, so a recipe looks unscheduled, or a cron is read as "no cron" and the recipe is silently
UNSCHEDULED. When a field is mysteriously empty, check the casing before anything else.

**What goose LISTS is not what goose ACCEPTS.** `config/extensions/list` returns an extension as
config.yaml spells it (`type: streamable_http`, `uri`, headers as a map); the add methods take a
tagged union of `builtin | platform | mcp`. Feeding a listed extension straight back fails with
`-32602`, and since setting a tool allowlist is remove-then-add, the extension is DELETED and the
error surfaces nowhere. `toExtensionDto` exists for this.

**Session-scoped ACP calls need the session's own stream.** Methods taking a `sessionId`
(`session/rename`, `conversation/append`, `session/project/update`) only answer on the transport
scoped to that session. Called without it they appear to hang while having already succeeded.

**`session/load` rewrites `working_dir` from the cwd the client sends.** Never guess a cwd on
resume: resolve it from the session list, the local cache, or ask the server. A guess silently
re-files sessions, and it has re-homed the assistant thread into the wrong directory before.

**Sessions are typed by who created them.** `session/new` with `_meta.client` present is a
`user` session; absent, it is `acp`. Desktop lists only `user` and `scheduled`, so omitting that
field makes every chat this app creates invisible in Desktop.

**Utility features get a session of their own, not the chat's.** `scanWithScratchSession` (code
scan) and `openBrowser` (directory picker) each open a private ACP session with cwd
`DEFAULT_CWD`, because both are reached from the drawer, where a chat is usually not open —
borrowing the current chat's session made the browser work only when a chat happened to be open.
Their state lives on `ConnectionManager`, not in any chat's state. And in `fs/list_directory`,
`parent` is null at a root: the server decides how far up you may go, and it refuses anything
outside its browse roots.

## Conventions

Match the surrounding code. Comments here explain *why*, and specifically why something is not
the obvious thing — several of the notes above started as an obvious change that was wrong.
Do not add comments that restate the code.

Prefer solving a problem in this app over changing the goose fork in `~/dev/goose`. A fork
carries every change through every rebase and rebuild forever; a client-side answer that is
nearly as good costs nothing ongoing. Fork changes are for behaviour the server genuinely does
not have.

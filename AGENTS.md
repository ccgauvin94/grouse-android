# Repository Guidelines

Grouse — a native Android client for a self-hosted goose (`goose serve`), spoken to over ACP.
AGPL-3.0. Alpha; sideload-only.

## Project Overview

The phone is a thin client. The server holds every piece of state that matters — sessions,
memory, extensions, model choice, recipes, schedules — and this app is an ACP client plus a chat
UI over it. When something looks like it needs local state, check first whether the server
already has it: several bugs here have been the app keeping its own copy of something the server
owns and the two disagreeing.

Two consequences worth internalising:

- **The phone shares no filesystem with the server.** A path typed here is a claim about a
  machine the app cannot see. The one such path the app holds is the configured working
  directory, supplied by the user at connect time; goose validates that `session/new`'s cwd is
  absolute and has no default of its own.
- **Other clients exist.** Goose Desktop and the CLI talk to the same server and change the same
  sessions. Anything cached here can be made stale by a client this app cannot see.

## Architecture & Data Flow

Single-process, single-activity Compose app. No ViewModels, no DI framework, no
StateFlow/LiveData — state is Compose snapshot state on one singleton.

- **`ConnectionManager`** — process-scoped singleton (deliberately *not* a ViewModel: it
  survives navigation/config changes and is shared with the service, tile, and receiver). It owns
  the ACP connection and **all** chat state as `mutableStateOf`/`mutableStateListOf`. Screens take
  the singleton `cm` as a parameter and read its state directly; Compose recomposes on change.
- **`AcpClient`** — thin JSON-RPC 2.0 client over an OkHttp WebSocket to `ws(s)://host:port/acp`.
  Integer request ids (AtomicInteger) correlate replies via a pending map; server pushes arrive
  as notifications and server requests (`session/request_permission`, `elicitation/create`,
  `recipe/request-params`), all surfaced as an `AcpEvent` (sealed interface, ~35 variants).
  **`onEvent` runs on OkHttp's WebSocket thread — callers must marshal to the main looper.**
- **Data flow** — UI → `cm.send…` → AcpClient request; server → `AcpEvent` → `cm` mutates
  snapshot state → Compose recomposes. Streaming assistant messages update in place: `ChatMessage`
  carries a stable AtomicLong `id` so the chat LazyColumn keys on identity, and `copy()` preserves
  it while text grows (composition reused, not rebuilt).
- **Connect lifecycle** — resolve the session list → `session/load` → transcript rebuilt from
  server replay (server = ground truth). **Never guess a cwd on resume: `session/load` rewrites
  `working_dir` from the cwd the client sends**; a guess silently re-files sessions. `session/new`
  sets `_meta.client=grouse` (a `user` session, visible in Desktop) — omit it and the session is
  `acp`, invisible everywhere except this app.
- **`ConnectionService`** — foreground `dataSync` service whose only job is keeping the process
  (and the WebSocket) alive; the socket itself lives in `ConnectionManager`. The
  battery-optimization exemption is a user-granted row in Instance settings.
  `GooseApp` tracks foreground/background via `ProcessLifecycleOwner` → `cm.setForeground`.
- **Push — server-driven through the MCP surface; the app only registers.** The goose server is
  the sender: it holds a `GROUSE_PUSH_ENDPOINT` value in its own server-side config and its
  senders (MCP extensions, recipes) POST to that endpoint URL. Grouse's only job is registration —
  `GoosePushService.onNewEndpoint` calls `cm.publishPushEndpoint(url)`, which writes the
  distributor-issued endpoint into server config via ACP `_goose/unstable/config/upsert`
  (`AcpClient.upsertConfig`). That write is also the self-heal for endpoint rotation: a reinstall
  mints a fresh uppush token, and the re-registered URL replaces the one the server was POSTing
  to. The distributor (e.g. NextPush) holds the one battery-friendly connection — no FCM, no
  per-app socket for receiving. `PushRegistry` is an optional *external* fallback registry
  (SecureStore `pushRegistryUrl`; blank = disabled). Received messages are an envelope
  `{type, session, text}`: `turn` → finished-turn nudge, shown only when the push matches a turn
  this device sent and it is backgrounded; anything else → briefing, always recorded for the
  Assistant status (`lastBriefingAt`/`lastBriefingText`), notified only when backgrounded.
  `Notifier` posts on channels `goose_ongoing` (LOW) and `goose_alert` (HIGH) with
  `MessagingStyle`; quick reply flows `ReplyReceiver` (`ACTION_REPLY`) → `cm.sendWhenReady`; taps
  deep-link into `MainActivity`.
- **Security** — secret key + server URL live in Keystore-backed `EncryptedSharedPreferences`
  (`SecureStore`, prefs file `goose_secure`; plain `goose` prefs for everything else);
  `hasKey()` gates every connect path. Optional `BiometricPrompt` lock
  (`BIOMETRIC_STRONG` + `DEVICE_CREDENTIAL`) overlays the app in `AppRoot`. Backup rules exclude
  `goose_secure.xml` and transcripts. TLS is trust-all + always-true hostname verifier
  (`Net.kt` accepts the self-signed cert rather than pinning it — tailnet + secret-key auth);
  ping interval 20s, read timeout 0.

### Branch policy

This checkout has only `master` (origin also carries `roam`). Older doctrine split `master`
(goose/ACP-only) from `phaethon` (`master` plus voice/STT/TTS, `VoiceAssistant`, the Android
Auto descriptor, `RECORD_AUDIO`, cleartext — none of which exist here). That split has partly
collapsed: **UnifiedPush (`Push.kt`, `GoosePushService`, the connector dependency) was restored
onto `master`** (commit `1961c2e`), so "push lives on phaethon" is no longer true.

The surviving rule is directional: a change that goes over the ACP socket belongs on `master`;
anything that reaches the network by another route should be flagged for branch placement before
it is added. Do not strand an ACP fix on a side branch expecting a merge to bring it back.

## Key Directories

|Path|What lives there|
|---|---|
|`app/src/main/java/id/gauvin/grouse/`|All Kotlin, one flat package (17 files)|
|`app/src/main/java/id/gauvin/grouse/ui/theme/`|M3 theme: dynamic color on API 31+, goose-green fallback, default typography|
|`app/src/main/res/`|Resources incl. `xml/backup_rules.xml`, `xml/data_extraction_rules.xml`, shortcuts|
|`app/src/main/assets/`|`chart.min.js` (Chart.js 4.4.4) — loaded by the chart WebView at `file:///android_asset/`|
|`docs/screenshots/`|README screenshots (drawer, chat)|
|`.github/workflows/`|`release.yml` — the only CI|
|Root|`README.md`, `CHANGELOG.md`, `AUDIT-20260803.md`, `ACP-AUDIT-20260803.md`, `env.sh`|

## Development Commands

```sh
source env.sh            # JDK 17 + Android SDK paths; there is no emulator on this box
./gradlew :app:assembleDebug          # APK lands in app/build/outputs/apk/debug/
./gradlew :app:compileDebugKotlin     # fast compile check
./gradlew :app:testDebugUnitTest      # JVM unit tests (no emulator needed)
./gradlew --stop                      # before moving/renaming the checkout (transform cache
                                      # records absolute paths; a moved tree confuses it)
```

Release builds need the keystore: `./gradlew :app:assembleRelease` with `GROUSE_*` env vars or
`grouse.*` Gradle props (locally from `~/.android/grouse-release.env`, per the comment in
`app/build.gradle.kts`). **`versionCode` MUST be bumped on every sideloaded build** — a
same-versionCode install is one Android may silently skip while reporting success. `versionName`
carries the date (`0.14-20260806`) so "which build is this?" is answerable from app info.

## Code Conventions & Common Patterns

- **Flat package, big files on purpose.** `Screens.kt` (3154 lines) holds every screen and is
  deliberately not split by feature. Match the surrounding code; comments explain *why*, and
  specifically why something is not the obvious thing. Never add comments that restate code.
- **DTOs mirror goose's wire shapes and keep the `raw` object.** `RecipeInfo.raw` is not
  optional: saving goes back through `recipes/save`, which replaces the entire recipe — an edit
  rebuilt from only the modelled fields silently drops extension allowlists, sub-recipe wiring,
  response schemas, retry config. `ExtInfo.raw` goes through `toExtensionDto` on the way back in.
- **State: singleton + parameter passing.** No DI, no ViewModels. New UI state goes on
  `ConnectionManager` as snapshot state, not into a new holder.
- **Client-side over fork.** Prefer solving a problem in this app over changing the goose fork in
  `~/dev/goose` — a fork change carries through every rebase and rebuild forever. Fork changes
  are for behaviour the server genuinely does not have.

### ACP protocol gotchas (the expensive ones)

- **camelCase vs snake_case, and the wrong one reads as null.** Most methods are camelCase;
  `recipes/list` returns `file_path` and `schedule_cron`, and `recipes/schedule` takes
  `cron_schedule`. A wrong spelling does not error — the field is simply absent, so a recipe
  looks unscheduled or a cron reads as "no cron" and the recipe is silently UNSCHEDULED.
- **What goose LISTS is not what goose ACCEPTS.** `config/extensions/list` returns an extension
  as config.yaml spells it (`type: streamable_http`, `uri`, headers as a map); the add methods
  take a tagged union of `builtin | platform | mcp`. Feeding a listing straight back fails
  `-32602`, and since tool-allowlist editing is remove-then-add, the extension is DELETED with
  the error surfacing nowhere. `toExtensionDto` exists for this.
- **Session-scoped calls need the session's own stream.** `session/rename`,
  `conversation/append`, `session/project/update` only answer on the transport scoped to that
  session; called on another they appear to hang while having already succeeded.
- **`session/load` rewrites `working_dir` from the cwd the client sends.** Resolve cwd from the
  session list, the local cache, or the server — never guess.
- **Sessions are typed by who created them.** `_meta.client` present → `user` session (Desktop
  lists it); absent → `acp` (invisible in Desktop).
- **MCP-App visualizations are server-hosted HTML, rendered client-side.** A `tool_call` whose
  `_meta.goose.mcpApp` names a `resourceUri` + `extensionName` expects the client to fetch the
  template via `_goose/unstable/resources/read` and render it. The template speaks JSON-RPC over
  postMessage to its PARENT frame (`ui/initialize` → `initialized` →
  `ui/notifications/tool-input` with the tool's arguments; height returns as
  `ui/notifications/size-changed`), so it must live in an **iframe** — `McpAppView` hosts it. A
  bare WebView is its own parent and the handshake loops back to itself. Do not use goose's
  `/mcp-app-proxy` route: loopback-only by design. Also: the chart tool's `data` argument arrives
  as a JSON OBJECT — parsing it only as a string once disabled every chart silently.
- **`session_info_update` is three notifications wearing one tag.** Title/rename updates,
  active-run lifecycle (`_meta.goose.activeRunId` — what makes `session/steer` possible), and
  queued-steer acks are distinguished only by which `_meta.goose` keys are present. Parse by key
  presence, never by assumed payload shape.
- **Utility features get a session of their own, not the chat's.** `scanWithScratchSession`
  (code scan) and `openBrowser` (directory picker) each open a private ACP session with cwd
  `DEFAULT_CWD`, because both are reached from the drawer where a chat is usually not open; their
  state lives on `ConnectionManager`, not in any chat. In `fs/list_directory`, `parent` is null
  at a root — the server decides how far up you may go.

## Important Files

|File|What lives there|
|---|---|
|`AcpClient.kt`|The wire: JSON-RPC over WebSocket, one `AcpEvent` per server message, parsers, DTOs, `upsertConfig`|
|`ConnectionManager.kt`|Process-scoped singleton owning the connection and all chat state; `publishPushEndpoint` (registers the push endpoint via ACP)|
|`Screens.kt`|Every Compose screen (Connect, Chat, Projects, Settings, Extensions, Providers, Instance, Recipes, Skills, Assistant) + `McpAppView` iframe host, `ChartView`, sheets/dialogs|
|`MainActivity.kt`|NavHost (12 routes), drawer, intent routing (share `SEND`/`SEND_MULTIPLE`, `NEW_CHAT` from the tile), biometric lock overlay|
|`SecureStore.kt`|Preferences; the secret key lives in Keystore-backed encrypted prefs; `pushEndpoint`/`pushRegistryUrl`|
|`Net.kt`|Trust-all OkHttp builder (accepts goosed's self-signed cert — no pinning)|
|`Push.kt`|UnifiedPush wiring: `Push` (enable/disable/refresh), `GoosePushService` (receive + register endpoint), `PushRegistry` (optional external fallback)|
|`app/build.gradle.kts`|SDK levels, versionCode/Name scheme, conditional release signing, all dependencies|
|`env.sh`|JDK/SDK detection — see Tooling|

## Runtime/Tooling Preferences

- **JDK 17 and the Android SDK; `env.sh` detects both rather than naming them** (deliberate —
  this checkout is built from the host `~/dev/grouse` and from the goose container, which mounts
  the same tree read-write at `/workspace/grouse`; their JDKs live in different places). A JRE
  is enough: the app is all Kotlin, so `javac` is never invoked. `ANDROID_HOME=$HOME/Android/Sdk`.
  No emulator on this box.
- **Never write a machine-specific path into a shared file.** Every hardcoded JDK path in
  `env.sh` was right for its author and broke the other machine.
- **Toolchain:** Gradle 8.9 wrapper, AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.02, minSdk 26,
  compile/targetSdk 34. `kotlin-stdlib` is forced to 2.0.20 because the UnifiedPush connector's
  transitive 2.3.0 metadata is unreadable by this compiler.
- **Signing:** the release `signingConfig` exists only when keystore path + three passwords are
  supplied; otherwise `release` falls back to the DEBUG keystore (fine for personal sideloads,
  never for distribution — anyone can sign an update over a debug key). NEVER commit the keystore
  (gitignored). CI hard-fails a debug-signed APK before publishing.
- **Two agents share this checkout** (host + container, no locking, both may edit the same file
  in the same minute): check `git status`/`git diff` before starting — uncommitted changes may be
  the other agent's. Do not commit or push unless asked; a sweeping `git add -A` picks up
  someone else's mid-flight work.

## Testing & QA

- **JVM unit tests** live in `app/src/test/java/id/gauvin/grouse/` (JUnit 4 + MockWebServer; no
  emulator, no Robolectric). They defend the contracts that have bitten repeatedly: the
  snake_case recipe fields, `toExtensionDto`, `session_info_update` key-presence dispatch,
  MCP-App tool_calls and the chart `data`-as-object trap, `_meta.client` on session/new, the
  `cron_schedule` casing on the wire, and push envelope parsing. Run with
  `./gradlew :app:testDebugUnitTest`. There are no instrumented/UI tests — verification of
  UI behavior is still `assembleDebug` + sideload on a device.
- **CI runs both on every push/PR to `master`**: `.github/workflows/ci.yml` builds the debug
  APK, `.github/workflows/test.yml` runs the unit tests (separate files so the build and test
  badges can fail independently). `release.yml` is unchanged: signed release APK on tags `v*`
  (or `workflow_dispatch`), apksigner debug-signing guard, `gh release` with the tag's
  CHANGELOG section + SHA-256.
- **Testability convention:** parsers and the dispatcher (`handle`) are `internal`, not
  `private`, so tests drive them directly without a socket. Keep pure parsing code free of
  Android dependencies — that is what keeps the suite JVM-only and fast.
- **Before touching wire code, read the audits:** `ACP-AUDIT-20260803.md` (protocol coverage:
  116 server methods vs ~45 used, with P0–P2 findings) and `AUDIT-20260803.md` (security audit,
  findings with fixes). They record what was already audited so you don't re-litigate it.
- `CHANGELOG.md` is per-release; the release workflow cuts notes from the matching `## <ver>`
  section.

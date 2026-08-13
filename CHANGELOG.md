# Changelog

Versions are the sideload-facing `versionName`; `versionCode` matches the minor number.
Only tagged releases appear here — locally-built numbers in between are skipped.

## 0.37-20260812

**Label-only projects, project editor, and a roam dial timeout.**

- Projects are labels now: no root directory, no .goosehints/memory viewer
  (removed). New projects take an optional pretty display name.
- Project editor (pencil icon on the project page): edit display name,
  description, and instructions — the body is injected into every chat filed
  under the project (server-side `load_project_instructions`).
- Project descriptions render under project names in the drawer; pretty names
  (frontmatter `name`/`properties.title`) display instead of kebab-case slugs.
- Roam "Test connection" no longer spins forever: the FFI dial has no timeout
  of its own, so a 12s watchdog now fails unreachable hosts cleanly.

## 0.36-20260812

**Streaming now resumes after the app loses focus — and says so.**

- On return to the foreground, if a turn was in flight but no chunk arrived in
  >10s, the app detects the silent socket death, reconnects, and re-replays a few
  times to pull in the turn (whether it finished in the background or is still
  running). A 1-2s background is unaffected — chunks keep flowing.
- The top bar shows **"Grouse · finishing…"** while that catch-up is in progress,
  instead of falsely looking idle.

## 0.35-20260812

**Tools sheet fixed for roam chats — and remoteness detection overhauled.**

- Tools tab in the chat settings sheet again loads the extension list (the merged
  sheet had dropped the priming call, so it could sit on "loading…").
- Roam-session detection now works end to end: `roamPeer()` falls back to the
  active connection (peer name of the on-screen roam session) because session ids
  never carry the "roam:" prefix the old check required. This fixes the Tools tab
  showing the LOCAL server's extensions for a roam chat, the top-bar hostname
  strip, the remote-session chip, "Lives on &lt;peer&gt;", and the Model tab's
  known-models guard for federated sessions.

## 0.34-20260811

**Top bar rework + live activity summary (dev).**

- Mode removed from the model picker app-wide (chat input pill, settings list).
- Model & tools pickers merged into one bottom sheet with Tools/Model tabs; the
  tool-count pill is gone — the Tune icon is the single entry point.
- Context moved to a colored fill-ring gauge in the top bar (green/amber/red by
  usage); tapping it shows used/max and the Compact button.
- Roam sessions show their hostname in a small translucent line under the top bar.
- Developer options: opt-in **live activity summary** — a fast-model session
  summarizes what the agent is doing into a one-line ticker while a turn runs
  (blank fast-model name forces it off).

## 0.33-20260811

**Providers screen works again — and shows your real providers.** Two bugs:

- Server-global settings (Providers, Instance, Extensions, Schedules, Recipes,
  Skills, Projects, the push endpoint) were routed through the ACTIVE
  connection — with a roam peer connected they read/wrote the PEER's config,
  so the toggles did nothing visible. They now always target the main serve
  host, and their replies update the screens even while a roam connection owns
  the chat.
- The provider pickers listed a hardcoded {openai, openrouter,
  openrouter_custom}; they now come from the server's actual provider inventory
  (`_goose/unstable/providers/list`) with real display names, and the chat
  panel's "configured providers" filter is server-derived instead of guessed.

## 0.32-20260811

**L0 guard polish.** The "Running in another goose" banner now self-expires: if
the other client's activity stops (including when the follow replay catches up
to an already-finished turn), the banner clears itself after 15s of quiet
instead of sticking on an idle session. And "Clear conversation…" now warns
explicitly when the session is running in another goose — clearing would
interrupt that run.

## 0.31-20260811

**"Running in another goose" guard (L0).** When the session on screen keeps
advancing while this device's connection has no active run, another client —
Desktop, the CLI, a second phone — is working it. Sending would start a second
concurrent loop against the same session (interleaved history, double tool
execution, no error on either side). The composer now shows "Running in another
goose — sending may conflict"; the first send tap arms a confirm, the second
sends anyway. The guard re-arms after your own turns and clears on session
switch. Same heuristic the roam web client ships.

## 0.30-20260811

**Streaming survives replay.** Session replays now preserve visible live/partial turn content instead of replacing it with an older server snapshot. This prevents replies from appearing late, disappearing, and only returning after switching chats.

The foreground reconnect workaround from 0.29 was removed because forcing a replay while a turn is still streaming caused more churn.

## 0.29-20260811

**Roam comes back up on launch.** The host you last connected to auto-connects at
startup (background thread, after serve is up), so its sessions are ready in the
Roam tab with no tap. Nothing saved → no-op; another dial already in flight → skip.

**Test a host before you trust it.** The Add-a-host button is now "Test connection":
it saves the card and immediately dials the host so you know it actually works.
Connect/disconnect load spinners show while a dial is in flight, on the host rows
(Roam screen + drawer) and in the button itself.

**Roam streaming survives a background-return.** A reply that was streaming when
you switched apps now picks up on return instead of pinning the chat at the point
it stopped: the app detects the quiet link and re-syncs, clearing the stuck
"thinking" state and replaying the transcript so the finished turn appears.

## 0.28-20260811

**New chat in Main always goes to the local host.** A "New chat" started from the
Main (SERVE) tab now creates the session on your goose serve, never on a roam
peer. Previously, once you'd opened a roam chat and switched back to the Main
tab, the drawer's "New chat" silently routed to whichever peer was last connected
(tabs switch the sidebar source only, not the connection) — so a chat you meant
for the host appeared in Roam. Projects-cafe and the tile/shortcut new-chat route
the same way now: projects are filed against the host, and the tile follows the
tab you're looking at.

## 0.27-20260811

**Two connections at once: Main and Roam tabs.** The serve connection and a
roam peer now stay up SIMULTANEOUSLY — connecting to a roam host no longer
kicks you off your main goose serve. The drawer gains Main/Roam tabs: Main
lists serve sessions/projects, Roam lists your endpoints (each a collapsible
group with connect/disconnect, like projects) and the connected host's
sessions. Opening a session or starting a chat on either tab switches the
on-screen conversation to that connection; the other stays connected in the
background.

**Stuck tools fixed.** Per-session tool/extension state is cleared when the
session changes, so switching back from a roam session no longer leaves the
peer's tools in the sheet.

**Disappearing provider fixed.** The model picker filtered providers through a
hardcoded local set ({openai, openrouter}); on a roam session the peer's real
providers got filtered out. Roam sessions now show the peer's full provider
list as reported.

## 0.26-20260811

**The roam transport actually works now** (core 0.1.3, from the upstream fix).
Three transport bugs, each hiding the next: the iroh pin drifted from the
hosts (1.0.2 → 1.0.3); the endpoint was dropped right after connecting, so
the first read saw "stream closed"; and — the real one — the stream's `read`
held a non-reentrant mutex across the blocking receive, deadlocking the instant
the first ACP frame arrived. The app sat at "connected — initializing" forever:
no session list, no new chats, no prompt replies. Now initialize round-trips
and the sessions load.

## 0.25-20260811

**New chats work on roam hosts.** `session/new` sent an empty cwd and goose
rejects non-absolute cwds, so "New chat" silently failed while connected to a
peer. The web client's convention is `cwd: "/"` — mirrored. Also: a roam
session is never filed into a local project (the peer owns its project ids).

## 0.24-20260811

**Connect crash instrumented.** The roam transport built with `panic=abort`,
so any Rust panic in the dial path killed the whole app with no message.
Panics now unwind to a catchable error — a failed connect shows "roam: …"
instead of crashing, and the full stack is logged. If it still hard-crashes
after this, the logcat will point at the faulting frame.

## 0.23-20260811

**Roam native transport loads at last.** Every uniffi call died with an opaque
`uniffi.grouse_roam_core UniffiLib` error since 0.19: the AGP default keeps
native libs inside the APK (`extractNativeLibs=false`), but JNA — the FFI layer
of the roam transport — loads libs via `System.loadLibrary`, which needs them
extracted at install; its APK-resource fallback looks under `android-aarch64/`,
a layout AGP never produces. Libs are now extracted (`useLegacyPackaging`),
so pairing and connecting work. APK size unchanged (~22 MB).

## 0.22-20260811

**Transport .so slimmed 63%.** The native roam core was 21.6 MB in the APK —
built with cargo's default release profile. A size profile (opt-level z + LTO +
strip + abort-on-panic) takes the .so to 7.9 MB with the FFI exports intact,
and the APK is now ~22 MB. The QR scanner's camera preview stays.

## 0.21-20260811

**APK back to sane size.** 0.20 ballooned to 58 MB: the ML Kit barcode engine
ships a ~19 MB native library across four ABIs for one QR decode. The scanner
now uses zxing (pure Java, ~700 KB, no natives), and the APK builds arm64-v8a
only — the sole native code is the roam transport, which was already arm64-only.
Back to ~36 MB.

## 0.20-20260811

**QR pairing for roam hosts.** The Roam screen's "Scan QR" opens a camera
scanner (CameraX + bundled ML Kit barcode — no Play Services) that reads the
host's `goose+roam://` card straight from the screen — no copy-paste. Pasting
the card by hand still works. Camera is an optional feature: the app still
sideloads on camera-less devices.

## 0.19-20260811

**Direct roam pairing (roam branch).** The app can now dial a `goose serve --roam`
(or `goose roam share`) host straight over iroh — no hub, no federation shim. The
peer IS a first-class goose: its sessions appear in the drawer, and chats, tools,
permissions and steer work like local ones. The iroh transport is a native Rust
core (`grouse-roam-core`, published as an .aar); the app speaks the same ACP
framing over the authenticated byte stream as goose uses on stdio.

- **Roam screen** (drawer › Roam): this device's public key, paste-a-card to add
  a host, connect/disconnect per host.
- Pair once per host: paste its card, then `goose roam peers accept <this key>`
  on the host. First connect shows the host's session list; picking a session
  resumes it (its real cwd is asked of the peer — never guessed).
- Also carries the master fixes merged in: turn-push nudge arming, transcript
  cache cap + mid-replay snapshots, resume-probe watchdog guard (10s window),
  live "Loading… N" replay progress.

## 0.17-20260810

**Transcript snapshot survives a kill during load.** The snapshot was skipped while a
session/load replay was streaming, so backgrounding mid-load (the most likely moment,
with a big session still loading) left no cache at all — the next cold start went
straight back to a blank "Loading…". Mid-replay the shown transcript is still a valid
snapshot (the pre-replay content or the cached paint), so it's now saved; the next
background after the replay completes overwrites it with the fresh transcript.

Verified against the server: `session/load` does NOT change a session's delta (only
one-time normalization writes, then stable), and the resume probe's messageCount is
live (computed from the messages table) — the "does loading change the delta?"
question is no.

## 0.16-20260810

**Replay shows live progress.** Loading a big session sat on a static amber
"Connecting…" with a blank chat until the whole history had streamed in — on a fresh
install there is no cached snapshot to paint, so a 1000+ message session looked hung
for the duration. The title now counts replayed messages ("Loading… 342") as the
stream arrives, so a long load reads as working.

## 0.15-20260810

Dev-branch test build (watchdog hardening + the 0.14.1-era fixes folded into master).

**Resume probe no longer restarts a live replay.** The 2.5-second dead-socket watchdog
fired while a big session was still replaying: the probe reply queues behind the replay
stream on a busy server, the window elapsed, and the app force-reconnected — restarting
the whole replay from zero. That made 1000+ message sessions look like they "time out
when fetching" and re-ran the entire history on every alt-tab. The probe and its watchdog
are now skipped while a replay is streaming (the chunks themselves prove the socket is
alive), and the dead-socket window is 10s.

**Finished-turn push nudges work again.** The gate compared against an armed-session id
that was never set (the arming was lost in the master ACP-only refactor), so no push ever
became a notification; arming restored on send, cleared on completion.

**Transcript cache is bounded.** Cold-start snapshots accumulated one file per session
forever; capped at 20, oldest pruned.

## 0.14 — 2026-08-06

**Push notifications are back.** 0.12 stripped UnifiedPush out along with the LocalAI
speech stack when master was made a generic ACP client, which was too broad a cut:
receiving pushes is generic, it just needs a distributor. Without it there was no
"register for push" option at all, and a server that had been given an endpoint kept
posting to a token nothing was listening on. Settings › Notifications registers with your
distributor (NextPush, ntfy, …); the speech code stays out of master.

**Connect accepts what people actually type.** The Host field took a bare hostname only,
so `https://goose.example.com/` — the thing you paste from a browser — failed with an
error that didn't say why. A scheme and a trailing path are now stripped, and a scheme
implies the port.

README now has screenshots.

## 0.12 — 2026-08-05

First public alpha, and the first signed release: a tag push builds the APK in CI, refuses
to publish if it came out debug-signed, and attaches the SHA-256.

Master became a goose/ACP-only client for this release — the server-specific pieces
(speech, directory browsing, cwd switching) moved to a private downstream. What ships here
talks to any `goose serve`.

## 0.40-20260812

**Per-endpoint session contexts — the model collision is gone.** Every
connection (serve + each roam endpoint) now keeps its OWN session state:
config options, model lists, tools, extensions, compaction and run tracking.
With two hosts connected on different providers, each chat shows and writes
ITS host's models — switching between them is instant because the context is
kept, never refetched. The follow/probe/watchdog machinery also targets the
active endpoint's OWN last session, so it can no longer probe (or reconnect)
peer B with peer A's session id — the source of the wrong-session errors and
reconnect loops after connecting a second host.

## 0.39-20260812

**Multiple roam endpoints at once.** Each saved host now keeps its OWN live
connection — connect A, connect B, and both stay up; the drawer's Roam tab
shows each connected endpoint's sessions, and tapping a chat on the other host
just switches to it (no re-dial, the connection was already there). Opening a
specific session still re-dials that host, exactly like switching chats on
serve. Disconnecting one host leaves the others and the serve connection
untouched. Transcript snapshots are keyed per host so same-named session ids
can't collide across them.

## 0.38-20260812

**Roam dial fixes — connecting works again.**

- `roamConnecting` was never cleared on a successful dial (only on failure and
  the sessionless path), so every Connect button stayed disabled after the first
  connect and auto-connect bailed on the stuck flag. It now clears the moment
  the link is handed over (plus a safety net at Ready).
- The 12s dial watchdog killed SUCCESSFUL connections too: nothing invalidated
  it, so at t=12s it tore down a live link. It's now cancelled when the dial
  lands and only fires for genuinely stuck dials.

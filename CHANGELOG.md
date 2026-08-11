# Changelog

Versions are the sideload-facing `versionName`; `versionCode` matches the minor number.
Only tagged releases appear here — locally-built numbers in between are skipped.

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

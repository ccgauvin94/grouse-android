# Changelog

Versions are the sideload-facing `versionName`; `versionCode` matches the minor number.
Only tagged releases appear here — locally-built numbers in between are skipped.

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

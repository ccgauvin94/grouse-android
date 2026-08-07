# Changelog

Versions are the sideload-facing `versionName`; `versionCode` matches the minor number.
Only tagged releases appear here — locally-built numbers in between are skipped.

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

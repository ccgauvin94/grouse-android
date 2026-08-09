# roamd — a long-lived roam endpoint on every machine

Grouse's REMOTE drawer shows sessions on roam peers, but a peer only exists
while someone has `goose roam share` running in a terminal there. This
document specifies the missing piece: making every endpoint a durable,
always-answerable roam peer.

## The finding: roamd is not a new program

`goose roam share` (goose fork, `feat/acp-federate-roam`) already is the
daemon body:

- hosts goose's **full ACP surface in-process** over the authenticated iroh
  transport (`FullAcpBridge` → `AcpServer`) — sessions created over roam are
  ordinary ACP sessions, stored in the endpoint's own session store;
- **TrustBook is re-read per connection**, so `roam peers accept`/`revoke`
  take effect against a live share without restart, and reload failure fails
  closed;
- scheduler is disabled in shares (no cron collisions with the hub);
- identity is one ed25519 key that *is* the iroh endpoint id — stable across
  reboots, IP changes, and networks, which is what makes a laptop reachable
  from a phone on LTE with no port forwarding.

So roamd is: **a systemd `--user` template unit around `goose roam share`,
plus a provisioning checklist.** No new binary, no new protocol.

## Unit

`~/.config/systemd/user/roamd.service`:

```ini
[Unit]
Description=roam endpoint (goose roam share)
Wants=network-online.target
After=network-online.target

[Service]
# One share = one working directory (see "cwd" below). Home is the
# broadest useful default; narrow to a projects root if preferred.
ExecStart=%h/.local/bin/goose roam share --cwd %h
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
```

`loginctl enable-linger $USER` so it survives logout. On hosts without
systemd user sessions (e.g. an HPC login node), a shell-rc guarded
`nohup goose roam share` is an acceptable degraded mode.

## Provisioning an endpoint

1. Build/install the fork's `goose` with the `roaming` feature.
2. `goose roam id` — prints the endpoint's connection card (non-secret).
3. Swap cards with the hub (the goose serve instance Grouse connects to)
   and `goose roam peers accept` **on both sides** — trust is a mutual
   public-key allowlist; a leaked card admits no one.
4. Point the endpoint's provider config at the model gateway so spawned
   agents use central models — the agent roams, the model does not.
5. Enable the unit. The hub's federation (`feat/acp-federate-roam`) folds
   the peer's sessions into its own session list; Grouse's REMOTE drawer
   picks it up with no app-side changes.

## Known limitation: cwd is forced per share

`new_session.rs` overrides the client-requested cwd with the share's
`--cwd` — deliberate, since a connector's absolute path is meaningless on
the host. Consequence: per-project working directories from the app need
either one share per root (heavy) or a small fork patch: honor the client
cwd when it resolves under an allowed root, force otherwise. That patch is
the only code change this design needs, and it is v1.1, not v1.

## Security posture

- Mutual ed25519 pinning (SSH-known-hosts style); QUIC-TLS proves key
  possession. No bearer tokens.
- Revocation is immediate (per-connection TrustBook reload) and needs no
  daemon restart.
- An accepted peer gets the **full ACP surface** — treat acceptance as
  granting shell-adjacent access to that machine (the developer extension
  is on by default in shares). Accept hub and phone keys only.
- Relays see encrypted QUIC; they cannot read or join sessions.

# goose-android

Thin native Android client for a self-hosted Goose agent (`goosed`), over the tailnet.

## What it is
The phone is a dumb pipe: `goosed` on phaethon holds all state (sessions, memory,
tools, model chain). The app is an ACP (Agent Client Protocol) client + chat UI.

## Server contract (captured 2026-07)
- Endpoint:  `ws://<phaethon-tailnet-ip>:3285/acp`
- Auth:      `X-Secret-Key: <GOOSE_SECRET_KEY>`  (header on the WS upgrade)
- Protocol:  ACP JSON-RPC over WebSocket
             initialize -> session/new -> session/prompt,
             stream session/update, answer session/request_permission
- Transport: tailnet only (no public exposure — goosed is RCE-capable)

## Stack (planned)
Kotlin + Jetpack Compose · OkHttp (WebSocket) · kotlinx.serialization (JSON-RPC)

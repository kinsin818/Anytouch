**Build note:** this is a **debug-signed test build** (version placeholder while the signing
keystore is being arranged). It is functionally the full app. A future release signed with the
official key cannot overwrite this build in place — Android signature rules mean you would need
to uninstall this build first, then install the signed one. No data carries over across that swap.

## Core features
- **BYOK visual automation** — bring your own OpenAI-compatible API key; the AI compiles a task
  into steps **only at task-creation time**, against the live Android node tree.
- **Record & replay** — record real UI interactions on your device, edit the step ledger, replay it.
- **AI-compiled tasks** — natural-language intent → editable step list; coordinates are never used,
  every step references nodes (resource-id / text / content-desc / path).
- **Three preset templates** — Photos cleanup (device-proven full chain on emulator), plus Gmail
  cleanup and Discord check-in (load-and-structure verified; **you must be signed into Gmail /
  Discord yourself** — the app never performs logins and never touches account credentials).

## Known limitations
- Tested devices: **Xiaomi K40 / K80 (China ROM) and AVD system images API 31 / 34 / 35**.
  **Samsung and Motorola devices are unverified** at this release.
- Gmail and Discord templates have not been executed end-to-end against live signed-in accounts
  (no test accounts by design); they ship verified at the load/structure level.
- App UI language is currently **Chinese only** (an English UI build is not part of this release).

## Privacy
- Your API key is stored **only in this device's Android Keystore** (encrypted at rest, never
  uploaded, never logged; the screen shows at most a masked tail).
- **Zero network access at execution time** — once a task is compiled, playback uses no model
  and no network.
- **No screenshots leave the device** — nothing is uploaded anywhere; the compile step sends the
  node tree and your own prompt to the endpoint *you* configured, and that is the only outbound call.

`app-debug.apk` MD5: `054993cc700fa3f7d3344bc85638d206` (9,374,132 bytes)

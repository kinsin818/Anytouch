**Build note:** this is a **debug-signed test build** (version placeholder while the signing
keystore is being arranged). It is functionally the full app. Because v1.0.0 and v1.0.1 are both
signed with the same Android debug key, installing this build **over** v1.0.0 keeps your data
(`adb install -r`, or just open the new APK). A future release signed with the official key cannot
overwrite either debug build in place — Android signature rules mean you would need to uninstall
first, then install the signed one. No data carries over across that swap.

## What changed since v1.0.0
- **The entire app UI is now English, and the language switch is gone.** Every visible label,
  button, hint, error line and confirmation panel ships in English only; there is no second
  locale and no in-app toggle to switch back. This build is the one intended for overseas users.
- `versionCode 2` / `versionName 1.0.1` (v1.0.0 was `versionCode 1`).
- Nothing else changed: no executor, compiler, safety-gate or storage **logic** was touched — only
  on-screen strings plus log/comment text. The same 5-round full-chain device run that qualified
  v1.0.0's Photos template was re-run on this exact build and passed identically.

## Core features
- **BYOK visual automation** — bring your own OpenAI-compatible API key; the AI compiles a task
  into steps **only at task-creation time**, against the live Android node tree.
- **Record & replay** — record real UI interactions on your device, edit the step ledger, replay it.
- **AI-compiled tasks** — natural-language intent → editable step list; coordinates are never used,
  every step references nodes (resource-id / text / content-desc / path).
- **Three preset templates** — Photos cleanup (full chain re-proven on this build, emulator with
  Google mobile services), plus Gmail cleanup and Discord check-in (load-and-structure verified;
  **you must be signed into Gmail / Discord yourself** — the app never performs logins and never
  touches account credentials).

## Known limitations
- Tested devices: **Xiaomi K40 / K80 (China ROM) and AVD system images API 31 / 34 / 35**.
  **Samsung and Motorola devices are unverified** at this release.
- Gmail and Discord templates have not been executed end-to-end against live signed-in accounts
  (no test accounts by design); they ship verified at the load/structure level.
- The device evidence for the Photos template is from **emulator system images**, not from an
  overseas retail phone — same node-tree path, but an emulator is not a phone.
- The high-risk confirmation panel still names the internal rule it matched
  (e.g. `High-risk action: DELETE:delete`, `matched field TEXT`) instead of plain prose. It is
  deliberately traceable; a friendlier wording is queued for your review, not shipped here.
- Engineering documents under `docs/usage-notes.md` and the closing backing table of the onboarding
  guide are internal records written in Chinese; they are not part of the buyer-facing guide.

## Privacy
- Your API key is stored **only in this device's Android Keystore** (encrypted at rest, never
  uploaded, never logged; the screen shows at most a masked tail).
- **Zero network access at execution time** — once a task is compiled, playback uses no model
  and no network.
- **No screenshots leave the device** — nothing is sent anywhere; the compile step sends the
  node tree and your own prompt to the endpoint *you* configured, and that is the only outbound call.

`app-debug.apk` MD5: `86833471bd0c134491ee78991c224d3b` (9,348,476 bytes)

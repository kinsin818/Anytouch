**Build note:** this is a **debug-signed test build** (version placeholder while the signing
keystore is being arranged). It is functionally the full app. v1.0.0, v1.0.1 and v1.0.2 are all
signed with the same Android debug key, so installing this build **over** either keeps your data
(`adb install -r`, or just open the new APK). A future release signed with the official key cannot
overwrite a debug build in place — Android signature rules mean you would need to uninstall first,
then install the signed one. No data carries over across that swap.

## What changed since v1.0.1
- **The high-risk confirmation panel now speaks plain English instead of engineering jargon.**
  When a step could destroy something, the panel reads:

  > This step needs your OK
  >
  > It may delete items here (photos, messages and the like). Deleted content is often gone for
  > good.
  >
  > Only confirm if you asked for this. If you don't answer, nothing runs.
  >
  > **[Cancel]**  **[Confirm and run]**

  Previously it printed the internal rule it had matched (e.g. `High-risk action: DELETE:delete`,
  `matched field TEXT`). That text is gone from the screen; the same attribution is still written to
  the run log and the run receipt, so nothing becomes harder to diagnose. **The gate itself is
  unchanged**: the same seven categories still trigger a second confirmation, and no answer within
  the timeout still means "refuse".
- **The buyer guide no longer carries internal test records.** The Chinese-language backing table
  that used to sit at the end of `docs/onboarding-gumroad-en.md` was moved out of the buyer-facing
  file into the engineering evidence folder.
- `versionCode 3` / `versionName 1.0.2` (v1.0.1 was `versionCode 2`).
- Nothing else changed: no executor, compiler, safety-gate **logic** or credential-storage code was
  touched — only the panel's display strings, one document, and our own test scripts. The same
  5-round full-chain device run that qualified v1.0.1's Photos template was re-run on this exact
  build and passed identically (13/13 assertions × 5 rounds), along with the 41-assertion UI suite.

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
- Because the core engine is open source, the repository still contains engineering records
  (test logs, adjudication notes, some of them in Chinese) under `evidence/` and `orders/`. Those
  are developer material, not documentation; the buyer guide is `docs/onboarding-gumroad-en.md`.

## Privacy
- Your API key is stored **only in this device's Android Keystore** (encrypted at rest, never
  uploaded, never logged; the screen shows at most a masked tail).
- **Zero network access at execution time** — once a task is compiled, playback uses no model
  and no network.
- **No screenshots leave the device** — nothing is sent anywhere; the compile step sends the
  node tree and your own prompt to the endpoint *you* configured, and that is the only outbound call.

`app-debug.apk` MD5: `775ebb89bef9f6e8632c4b07d0ab2eb6` (9,290,901 bytes)

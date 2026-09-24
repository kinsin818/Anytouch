# Anytouch

**Say the task in plain English — your own AI model turns it into concrete, editable steps on your
Android phone. After that, playback runs 100% on-device: no model, no account, no network.**

Anytouch is a bring-your-own-key (BYOK) Android automation app. Unlike macro recorders, you don't
edit scripts or record blind click-coordinates: you describe what you want ("open the page where
Bluetooth can be turned on"), and an OpenAI-compatible model **you** choose compiles that intent
into a step ledger (tap / scroll / type / wait only) that you can see, rename, reorder or delete.
The AI work happens **once, at creation time**. Playback references the accessibility node tree —
never screen coordinates — and refuses to act on anything it cannot match, honestly reporting
`NO_MATCH` instead of tapping something random.

> A complete end-user install & start guide lives in
> [`docs/onboarding-gumroad-en.md`](docs/onboarding-gumroad-en.md). This README is the repository
> overview. Every capability claim below is backed by an engineering record on disk in this
> repository (`evidence/`); where something is *not* verified, it says so.

## Features (current build)

- **AI compile with your own key** — any OpenAI-compatible endpoint (`/v1/chat/completions`).
  The app ships with zero bundled AI access.
- **Hardware-encrypted key storage** — API keys are sealed with the Android Keystore
  (AES-GCM via a hardware-backed key) in app-private storage. After saving, the screen shows only
  a masked tail (`***Ab12`); the full key never reappears on screen, is never written to any log,
  and is sent to exactly one destination: the base URL you typed. Localhost / link-local /
  cloud-metadata endpoints and redirect following are rejected by design.
- **On-screen words, on your terms** — an explicit switch controls whether the *visible text
  labels and element ids of the current screen* (never input-field contents, never passwords,
  never screenshots) are uploaded together with your intent, so the model can name real buttons.
  The exact number of uploaded lines is displayed on screen with every compile result.
  Switch off ⇒ zero lines leave the phone.
- **Compile-time exclusivity** — recording, running a task and editing steps all refuse to start
  while a compile holds the state, with the refusal shown on screen.
- **A step ledger you own** — compiled steps land in the task box where they can be renamed,
  reordered or deleted through a single audited write path. The executor's action vocabulary is a
  single source of truth shared with the compiler; anything outside
  `{click, scroll, type_text, wait}` is rejected wholesale, by name.
- **Zero-network playback** — execution never touches the network. Airplane-mode playback is a
  shipped acceptance check (behavioral evidence: red-line static scan of the product's execution
  paths for network calls, plus an emulator airplane-mode test suite — see
  [`evidence/S2/s2-ondevice-record-replay.md`](evidence/S2/s2-ondevice-record-replay.md) and
  [`evidence/S3/slice-e1-emulator-pre.md`](evidence/S3/slice-e1-emulator-pre.md)).
- **Node-tree referencing, coordinates banned** — the compiler rejects any model output that
  smuggles coordinates; steps reference UI elements by structure and re-locate them at playback.
- **High-risk confirmation, refusal by default** — steps that could spend money or send data
  require an explicit on-screen confirm and **time out to refuse** if you don't. No silent
  dangerous actions.
- **Floating kill-ball** — a system-overlay stop ball aborts a running task instantly; if it
  cannot be mounted, execution refuses to start (fail-closed), and every abort leaves an
  attributable receipt (`user_stop`).
- **Honest failure** — dropped injections, expired requests, service interruptions and unverified
  text entry all produce visible receipts. Interrupted runs write `SERVICE_INTERRUPTED` instead of
  showing a stale result.

## Quick start

1. **Install**: sideload `anytouch-debug.apk` (there is no Play Store listing). Grant
   "install unknown apps" to your file manager when your ROM asks.
2. **Grant two permissions**: *Accessibility* (so the app can read the node tree and act) and
   *Display over other apps* (the floating stop ball). Without either, the app refuses to record
   or execute and names the missing one on screen.
3. **Add your key**: in the credential panel enter Base URL, model name and API key, then Save.
4. **Compile a task**: type an intent, optionally keep *use on-screen words* on, tap *AI compile*.
5. **Run it**: tap *Run task*; tap the stop ball any time to abort.

> UI labels in the current build are Chinese; the mapping table in
> [`docs/onboarding-gumroad-en.md`](docs/onboarding-gumroad-en.md) names every label verbatim.

## Building from source

Requirements: JDK 17, Android SDK (compileSdk 36, targetSdk 34, minSdk 26).

```bash
./gradlew :app:assembleDebug            # APK at app/build/outputs/apk/debug/
./gradlew test                          # JVM unit tests (app / byok / contracts modules)
bash scripts/ci-local.sh                # full local gate incl. red-line static scans
bash scripts/redline-probe.sh           # self-test that the red lines can still FAIL
```

`scripts/device-smoke.sh`, `scripts/ui-smoke.sh`, `scripts/s2-smoke.sh` and
`scripts/s4-fullflow-k40.sh` are the on-device regression harnesses (adb required; they assert
their own preconditions — unlocked screen, bound accessibility service, device exclusivity lock —
and abort loudly with the reason instead of failing silently).

## Repository layout

| Path | What it is |
|---|---|
| `core/contracts/` | Frozen contract types (Action, Step, receipts). Do not extend casually. |
| `app/` | The Android app: UI, accessibility service, recorder, executor. |
| `:byok` (`byok/`) | Android-free BYOK module: OpenAI-compatible transport, base-URL policy, key masking, Keystore-backed storage, screen-context minimization, `ExecutorVocabulary` single source of truth. |
| `tools/compiler/` | Host-side compile probe (dev tooling; never ships in the APK). |
| `docs/` | End-user guide (English). |
| `evidence/` | Timestamped engineering records: raw device logs, run ledgers, acceptance proofs. The backing table in the user guide maps each public claim to a file here. |
| `orders/` | Internal build orders and rulings (the process this project is built under). |

## Privacy policy (plain words)

- **Your API key** is stored encrypted with your phone's hardware Keystore, in app-private
  storage. It is sent only to the endpoint you configure, only during compile, and never to us —
  there is no "us" to send it to: the app contains no analytics, no crash reporting, no
  telemetry, no account system and no first-party server of any kind.
- **Your screen content**: only with the *use on-screen words* switch on, the app uploads the
  visible text labels and element ids of the current screen, with the exact line count displayed
  on screen. Input-field contents, password fields and screenshots are excluded by design and
  never uploaded. With the switch off, nothing leaves the phone during compile besides your
  typed intent.
- **No screenshot pixels ever leave the device.** Playback reads the accessibility node tree,
  not pixels.
- **Clear credentials** deletes both the encrypted blob and the Keystore key, and re-reads to
  confirm they are gone.
- Removing the app removes everything: there is no cloud copy because there is no cloud.

## Open-source license

Anytouch is free software, released under the **GNU General Public License v3.0 or later**
(see [LICENSE](LICENSE)). This covers the entire repository, including the core execution engine,
as required by the project's delivery terms.

- You may run, study, modify and redistribute it, provided derivatives stay under GPLv3 and
  preserve the license and source availability.
- The software comes **with absolutely no warranty**, to the extent permitted by law; it is
  distributed in the hope it will be useful, but **without any implied warranty of merchantability
  or fitness for a particular purpose**. Automated UI manipulation can misfire on apps you didn't
  review the steps for. You are responsible for what the steps do on your own device and accounts.
- Anytouch is not affiliated with Google, with any app it automates, or with any AI provider.
  It deliberately performs **no** CAPTCHA solving, no account login, no store purchases without
  the explicit confirmation gate described above.

## Known limitations (honest list)

- **One device family hands-on verified** (Xiaomi K40 / MIUI, plus Redmi K80 / HyperOS for
  ROM-quirk mapping). **Samsung and Motorola devices have never been hands-on tested by the
  developer**; coverage there is emulator matrices (AOSP-class images), and emulators cannot
  catch vendor-ROM quirks. Expect unscripted permission prompts on heavily-skinned ROMs; the app
  fails openly there.
- Compile quality depends on the model you bring. Simple navigation intents are strong; long
  multi-app workflows are the frontier.
- On MIUI-class ROMs, injected text into certain search fields is persistently refused at the
  platform level; the app reports the failed step and its count instead of pretending success.
- Recording, compiling and playback have been device-verified end-to-end in segments under the
  `evidence/` ledger; a single uninterrupted full-chain green run on a real device is recorded as
  "not achieved, accepted by project decision" — see `evidence/S4/slice-s4a-fullflow.md`.
- The in-app UI is Chinese in this build; an English UI is not shipped in this build.
- **Preset templates ship at two verification tiers.** *Photos trash cleanup* is proven end-to-end
  on emulator images carrying Google mobile services. *Gmail cleanup* and *Discord check-in* are
  loader- and structure-verified only (JVM locks): their business steps have never been run against
  a live signed-in account (no test accounts are provided, by decision), and the Discord app has
  never been installed on any of our target emulators — its locators are best-effort. Both require
  you to be signed in yourself; the app never performs or reads logins.

## Support

For a paid copy, updates and the install guide, use the distribution channel you bought from.
For the source of truth on behavior, read `evidence/` — every public promise in this README and in
the user guide is traceable to a file there.

## License note for distributors

If you redistribute builds, keep this README, the `LICENSE` file and the `evidence/` directory
intact, so downstream users can audit claims against records.

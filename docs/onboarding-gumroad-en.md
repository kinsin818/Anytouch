# Anytouch — Install & Start Guide (Gumroad edition)

> This is the guide that ships with the app. Every capability sentence in it was checked against
> our own build before it went in, and the places where something is **not** proven say so plainly
> ("What this build is" and the troubleshooting table below).

---

## What Anytouch is (30 seconds)

Anytouch is an Android automation app that **writes its own recipes**. You type a task in plain
English — "open the page where Bluetooth can be turned on" — and the app turns it into concrete
taps, scrolls and text steps. The AI work happens **once, at creation time**, using **your own**
API key (any OpenAI-compatible endpoint). After the steps are created, playback runs entirely on
your phone: **no model, no account, no network traffic during execution**.

This is what separates it from macro recorders: you don't record clicks and don't edit scripts.
You say what you want, the app compiles it into a step ledger you can see, rename, reorder or
delete — and then it just works offline.

## What you need

| Requirement | Detail |
|---|---|
| Phone | Android 8.0 (API 26) or newer. Verified hands-on: Xiaomi K40 / Android 13 / MIUI V816. Other brands follow the same steps; some heavily-skinned ROMs add extra toggles (see Troubleshooting). |
| Model access | Your own OpenAI-compatible endpoint + model name + API key (hosted or self-hosted). The app ships with **zero** bundled AI access. |
| Storage | ~10 MB app. |

## Install (sideload — there is no Play Store listing)

1. Copy `anytouch-debug.apk` to the phone (any way you like: USB, file app, download link).
2. Tap the file → allow **"install unknown apps"** for your file manager when MIUI/ColorOS/etc. asks.
3. Open Anytouch once so it can register.

## First launch — two permissions

Anytouch needs exactly two system permissions, and **refuses to start recording or executing
without them** (it will tell you on screen instead of failing silently):

1. **Accessibility** — this is how the app reads the screen structure and performs taps.
   Settings → Accessibility → Installed apps → Anytouch → **On**.
2. **Display over other apps** — this gives you the floating **stop ball**: tap it at any time
   to abort a running task immediately.

There are no other *dangerous* permissions. The full manifest list is: INTERNET (the only thing
that ever talks, and only during compile), a foreground-service + notification slot (so Android
doesn't kill a running task out from under you — Android 13+ may ask you about notifications),
and nothing else. **No camera, no microphone, no contacts, no location, no storage access.**

## Connect your own key (BYOK)

In the app's credential panel enter: Base URL (e.g. `https://api.example.com/v1`), model name, and
your API key, then **Save**.

- The key is stored **encrypted with the phone's hardware Keystore**, in the app's private storage.
- After saving, the screen shows only a masked tail (`***Ab12`). The full key never appears on
  screen again, is never written to any log file, and is sent to **one destination only: the base
  URL you typed**.
- **Clear** deletes both storage slots (encrypted file + Keystore key); the app confirms it's gone.
- Local/self-signed metadata addresses (localhost, link-local, cloud metadata endpoints) are
  rejected by design — the key cannot be tricked into leaving the device network.

## On-screen labels (verbatim, from the device sweep)

From **v1.0.1** the whole UI is English and there is no language switch (v1.0.0 and earlier shipped
Chinese labels). The right-hand column below is copied verbatim off the running app — the same
strings the `scripts/ui-english-sweep.sh` dump captured on an API-34 emulator, so if a future build
drifts, this table is what changes.

| This guide says | On the phone you tap / see |
|---|---|
| Save (key panel) | **Save** |
| Clear credentials | **Erase device credentials** |
| Base URL field | **Service URL (OpenAI-compatible endpoint, https only)** |
| Model field | **Model name** |
| API key field | **API key (after saving, only its last 4 digits stay on screen)** |
| Credential state line | **Key saved on this device: \*\*\*XXXX** |
| Where the key goes | **Your key is sent to exactly this one address: integrate.api.nvidia.com** |
| Compile button | **AI compile** (panel header: **AI compile (bring your own key — network is used only when creating a task)**) |
| Intent box | **Say what to do in one sentence (e.g. open the Bluetooth page)** |
| Screen-word switch | **Attach the visible text of the current screen (only on-screen labels and control ids are sent; text-field contents, password fields and screenshots never are)** |
| Run task | **Run task** |
| Step ledger header | **Steps 9 (delete / rename / reorder only touch this step ledger)** — the number is the step count |
| Empty ledger | **The step ledger is empty: record first and tap “Stop & compile”, only then do steps appear here.** |
| Step editor | **Step name** · **Rename** · **Up** · **Down** · **Delete** |
| Start / stop recording (manual recorder, optional path) | **Start recording** / **Stop & compile** |
| Target app package field | **Package to record** |
| Preset templates | **Photo cleanup** · **Gmail cleanup** · **Discord check-in** |
| Screen title | **Anytouch executor** |


## Compile your first task

1. Open the target app (say, Settings) so it is on screen.
2. In Anytouch, type the intent, optionally keep **"use on-screen words"** on, and tap **Compile**.
3. What that switch does: with it on, the app uploads the **visible text labels and element ids of
   the current screen** (not input-field contents, not screenshots, not any user data) so the
   model can name real buttons. The exact number of uploaded lines is shown on screen with the
   result, before and after every request. With it off, zero lines leave the phone.
4. The result: a step list (tap / scroll / type / wait only — the compiler will refuse anything
   else and tell you why). The steps appear in the task box, where you can rename, reorder or
   delete each one.

## Run it

Tap **Run task**. The floating ball appears; tap it to abort instantly.
High-risk steps (things that could spend money or send data) demand an explicit on-screen
**confirm** — and time out to **refuse** if you don't. No silent dangerous actions, ever.

Execution uses **no network**: airplane-mode playback is a shipped acceptance check — backed on disk by a static
red-line scan (zero network calls anywhere in the product's execution path) and an
emulator airplane-mode playback run at 13/13. The honest boundary: that is **behavioral** evidence, not a packet capture.

## Preset templates (no key needed)

Three ready-made task ledgers ship inside the app — load one with a tap and it lands in the step
ledger exactly like a compiled task (same gate, same editability, same high-risk confirm):

| Template | What it does | Verified to what level |
|---|---|---|
| **Photos cleanup** | Empties the Google Photos trash permanently, end to end | Full chain proven on an emulator with Google mobile services (Android 14/15 class images): load → ledger → playback → trash physically emptied |
| **Gmail cleanup** | Opens Gmail and filters to unread mail via search | Loader + structure checks only (JVM); business steps not run against a live signed-in inbox |
| **Discord check-in** | Types a daily check-in message into the focused channel and sends it | Loader + structure checks only (JVM); business steps not run against a live signed-in account |

**The Gmail and Discord templates require you to be signed in to those apps yourself.** Anytouch
never performs logins, never reads your credentials and never stores them — in BYOK spirit, your
accounts are yours; the app only automates the screen after you are already in. The template
buttons are also inert while a compile is in flight (one mutable state at a time, on purpose).

Known vocabulary gap, stated honestly: the current step vocabulary (click / scroll / type_text /
wait) cannot express *bulk* "mark as read" in Gmail (no long-press / IME-submit step type exists
yet), so the Gmail template ships as the unread-**triage** subset above — not a bulk cleaner.

## Privacy, in one paragraph

Your API key and your screen content are only ever sent to the endpoint you configured, only while
compiling, only with the amount shown on screen. Nothing runs in anyone else's cloud. There is no
account, no analytics, no crash reporting, no screenshot capture anywhere in the product
(no screenshot pixels are ever uploaded, and playback reads the screen structure, not pixels).
Recording, editing and playback all work fully offline; only the compile step ever touches the
network, and only to the endpoint you chose.

## Troubleshooting

| Symptom | What it means |
|---|---|
| `401 / 403` after Compile | Key or endpoint rejected by your provider. Check key validity; re-save. |
| `429` | Your provider's quota is busy. Retry later — this is on your side of the BYOK deal. |
| `Timeout / unreachable` | The app never follows redirects and speaks HTTPS only; corporate/captive-portal Wi-Fi can break this. Try mobile data. |
| A step fails during playback | Steps reference screen elements by structure. If you changed the **target app's** language or layout (Anytouch's own UI is English-only and never switches), recompile that step — old ledgers honestly report `NO_MATCH` rather than tapping something random. |
| Text steps do nothing on some MIUI builds | MIUI is known to persistently refuse injected text in certain search fields (hardware-level denial on that ROM). Anytouch reports the failed step and its count honestly instead of pretending the text was typed. |
| Recording / executing refuses to start | Either accessibility or the overlay ball is off — the red line on screen names which one. |

## What this build is, honestly

- A **debug-signed sideload build**. It will not appear on Google Play; do not expect Play auto-updates.
- Compilation quality depends on the model you bring. Simple navigation intents are strong;
  long multi-app workflows are the frontier. The step ledger is always editable, so a wrong step
  is a fix, not a trap.
- Hands-on verified: one device family (Xiaomi/MIUI). **Samsung and Motorola devices — the largest
  Android brands overseas — have never been hands-on tested by us**; coverage there is an emulator
  matrix (Android 12/14/15 class images) only, and emulators cannot catch vendor-ROM quirks.
  Other ROMs may add permission prompts we have not scripted for; the app fails **openly** there
  instead of tapping something random.

---

## Appendix A — Gumroad listing blurb (paste-ready)

> **Anytouch — say the task, get the automation. BYOK, on-device playback.**
> Type a plain-English task. Your own AI model (any OpenAI-compatible key) compiles it into
> visible, editable steps on your phone. After that, playback is 100% offline: no account,
> no cloud, no meter running. Hardware-encrypted key storage, per-request screen-word upload you
> can watch and switch off, floating kill-ball, and refusal-with-an-error-message instead of
> silent failure. Android 8+. Includes: app APK + install & start guide + 3 preset task templates
> (Photos trash cleanup — fully proven on device; Gmail and Discord helpers — require your own
> sign-in, the app never touches your credentials). Bring-your-own-key:
> the app ships with zero AI access of its own.

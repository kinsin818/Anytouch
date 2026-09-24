# Anytouch — Install & Start Guide (Gumroad edition)

> Status: DRAFT v1 for S4-b (military order `orders/ANYTOUCH-S4-packaging-ORDER.md` §1/§3).
> Every capability sentence below is back-traceable to an on-disk engineering record (Appendix B).
> Sentences marked **[pending S4-a]** must be re-checked against the S4-a run log before publishing.
> **Language note: written by the build agent, not proofread by a native speaker — boss review required before listing.**

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

## On-screen labels (current build UI is Chinese — honest mapping)

English localization of the app UI is **not done yet**. This build shows Chinese labels; here is
the exact mapping this guide refers to:

| This guide says | On the phone you tap / see |
|---|---|
| Save (key panel) | **保存** |
| Clear credentials | **清除本机凭据** |
| Base URL field | **服务地址（OpenAI 兼容端点，仅 https）** |
| Model field | **模型名** |
| API key field | **API Key（保存后屏上只留尾 4 位）** |
| Compile button | **AI 编译**（panel header: **AI 编译（用自己的 Key，只在创建任务时联网）**） |
| Intent box | **用一句话说要做什么（例：进蓝牙页）** |
| Screen-word switch | **带上当前屏幕的可见词表（只上行可见文字与控件 id；输入框内容、密码框、截图一律不上行）** |
| Run task | **执行任务** |
| Start / stop recording (manual recorder, optional path) | **开始录制** / **停止并编译** |
| Target app package field | **录制目标包名** |

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

Execution uses **no network**: airplane-mode playback is a shipped acceptance check. **[pending
S4-a: tie to the S6a/S6d/S6e/S6f green lines of a *quiescent* run under `evidence/S4/raw/` — r4's green S6 lines exist but the round was voided (dual-driver contamination, see `evidence/S4/slice-s4a-fullflow.md` §3-5)]**

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
| A step fails during playback | Steps reference screen elements by structure. If you changed the app's language or layout, recompile that step — old ledgers honestly report `NO_MATCH` rather than tapping something random. |
| Text steps do nothing on some MIUI builds | MIUI is known to persistently refuse injected text in certain search fields (hardware-level denial on that ROM). Anytouch reports the failed step and its count honestly instead of pretending the text was typed. |
| Recording / executing refuses to start | Either accessibility or the overlay ball is off — the red line on screen names which one. |

## What this build is, honestly

- A **debug-signed sideload build**. It will not appear on Google Play; do not expect Play auto-updates.
- Compilation quality depends on the model you bring. Simple navigation intents are strong;
  long multi-app workflows are the frontier. The step ledger is always editable, so a wrong step
  is a fix, not a trap.
- One device family is hands-on verified (Xiaomi/MIUI). Android-fragmentation honesty: other ROMs
  may add permission prompts we haven't scripted for. The app fails **openly** there.

---

## Appendix A — Gumroad listing blurb (paste-ready)

> **Anytouch — say the task, get the automation. BYOK, on-device playback.**
> Type a plain-English task. Your own AI model (any OpenAI-compatible key) compiles it into
> visible, editable steps on your phone. After that, playback is 100% offline: no account,
> no cloud, no meter running. Hardware-encrypted key storage, per-request screen-word upload you
> can watch and switch off, floating kill-ball, and refusal-with-an-error-message instead of
> silent failure. Android 8+. Includes: app APK + install & start guide. Bring-your-own-key:
> the app ships with zero AI access of its own.

## Appendix B — backing table (internal, remove before publishing)

| Sentence in this guide | Disk evidence |
|---|---|
| Keystore 加密持久化 / 掩码尾 4 / 清除双槽 / 必回读比对 | `evidence/S3/slice-e2-k40-key.md` [K4] 段 E9a~c；E11a~d（老板亲点）见同文件 §6 + `orders/ANYTOUCH-S3-byok-ORDER.md` §5.1 "E 收口" 行 |
| 地址政策拒本地元数据网段/重定向/userinfo | `evidence/S3/slice-b-key-surface.md`（BaseUrlPolicy 九档） |
| 上行=可见 text/resource-id，不含输入框内容与截图，条数上屏 | `orders/ANYTOUCH-S3-byok-ORDER.md` §0 裁 2 + `evidence/S3/slice-c-screen-context.md`；E5c/E5d/E10 rows 实采（`slice-e2-k40-key.md`） |
| 执行期零网络（行为证据） | **[pending S4-a]** K40 全链 S6 组现无有效轮：r4 绿读数在档但整轮因双驱动互污作废（`evidence/S4/slice-s4a-fullflow.md` §3-5，raw r4 逐字保留）；模拟器侧飞行模式 device-smoke 13/13 见 `evidence/S3/slice-e1-emulator-pre.md`——AVD≠真机，本行不冒顶，待独占轮补 |
| 编译互斥四入口（跑着不许串状态） | commit 72af452，`evidence/S3/slice-f-mutex-vocab.md` |
| 词表只有 click/scroll/type_text/wait，越权整本拒并显式 | `ExecutorVocabulary` 真源 + E5i 真机命中（`slice-e2-k40-key.md` §6） |
| 高危动作二次确认/超时默认拒 | `evidence/S2/stage-highrisk-confirm-device.md`（设备实证：超时默认拒绝+面板可见） |
| 无障碍/悬浮球缺席=拒开录拒执行并指名缺哪个 | L1/L2 门禁（task #30；ui-smoke U 系列） |
| MIUI 持久拒 SET_TEXT 大声报不冒成功 | 雷 12（`evidence/S2/t3-k40-first-contact.md`）+ S31-B8 真机复验 `evidence/S31/stage31-b-k40-reverify.md` |
| minSdk 26 / 实测机型 K40 | `app/build.gradle.kts`；T3 档案 |

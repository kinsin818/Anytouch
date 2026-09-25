**Anytouch 1.0.5** — three advanced features now sit behind an activation code that is checked on the device only. No server, no account, no network call at any point.

## What's new

- **Activate on the home screen.** A new button opens a code field. A code in the form `ANY-XXXX-XXXX-XXXX` unlocks three things: the repeat loop, My tasks (save / load / reuse a step list), and manual step insertion. Until then those three entries are greyed out with a one-line hint — and they are refused the same way through the automation channel, because a greyed-out button is not a gate.
- **The check is local, all of it.** Length, the separator positions, the character set and the two-letter checksum are evaluated on the phone. A rejected code tells you which part looked wrong and unlocks nothing. The code is never displayed in full anywhere on screen or in a log — only its last four characters, the same rule the API-key screen already follows.
- **Unlocked stays on this device.** The flag is written into the app's own private storage (temp file, rename in place, read back and compare bytes), so it survives an app update and a reboot. Resetting it puts the wall back exactly where it was.
- **Nothing about safety moved.** High-risk steps still ask for confirmation every single time, the 15-second timeout still defaults to *no*, the floating stop ball still stops the run, and recording a task or running it once is still free. The paywall sits behind those gates, never in front of them — that ordering is locked by unit tests, not by intent.

## Notes

- Debug-signed build, same as the earlier releases here; installing over a previous 1.0.x keeps your saved tasks and settings.
- `app-debug.apk` — 9,532,419 bytes, `versionCode 6` / `versionName 1.0.5`, md5 `3128921ab9a1a0ee78db2c7338e4f1a8`.
- Execution remains zero-model and zero-network: the app compiles a task into a step list once, then plays that list back without calling anything.

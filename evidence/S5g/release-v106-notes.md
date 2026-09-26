**Anytouch 1.0.6** — from this version a purchase code is validated against our server, once per device. Everything before this release validated it on the phone, and everything after this release still runs with no network at all.

## What changed

- **Activation now asks us.** When you enter a code on a device for the first time, the app sends one request: the code, plus a truncated SHA-256 hash of this device's Android ID. The server answers *ok* or *no*. Up to and including **v1.0.5** the same codes were checked entirely on the device, and that local format check is still the first gate — a malformed code is refused on the phone without ever leaving it.
- **One code covers 2 devices.** A third device is refused with a clear reason. If a device of yours goes away (factory reset, replacement), freeing the seat is one support call away — the binding is the only record we keep, and it is not a locked door.
- **One request, then silence.** That activation call is the only thing the app ever sends to us. It carries no name, no email, no phone number, no task, no step list, no prompt, no screen text, and never your API key. After the device is activated, recording, editing, compiling and playback are all on-device and offline: no model, no account, no meter.
- **The connection is encrypted and pinned.** Self-signed certificate, TLS 1.2 or newer, and the app checks the server's public-key fingerprint rather than trusting whatever a certificate authority hands out. If the fingerprint doesn't match, the app refuses the response.
- **A device with no route to that endpoint cannot be activated for the first time.** It says so plainly (`Can't reach the activation server`) and unlocks nothing. A device that already activated keeps working with no network at all.

## Unchanged

- High-risk steps still ask for confirmation every single time; the 15-second timeout still defaults to *no*; the floating stop ball still stops a run; the accessibility gate is still a precondition, not a warning.
- Recording a task, compiling it and running it once are still free — the wall only ever gates the three advanced features (repeat loop, My tasks, manual step insertion), and it never replaces or relaxes one of those gates. That is locked by unit tests, not by intent.
- Execution remains zero-model and zero-network: the app compiles a task into a step list once, then plays that list back without calling anything.

## Notes

- Debug-signed build, same as the earlier releases here; installing over a previous 1.0.x keeps your saved tasks and settings.
- `app-debug.apk` — 9,389,205 bytes, `versionCode 7` / `versionName 1.0.6`, md5 `d61b9867fcdf164d73ed236ce61f76e1`.

# Omi compatibility notes — public source edition

Protocol reference: [BasedHardware/omi at d1fdcb4cc4fbd021eac799630e476f144e6bc18a](https://github.com/BasedHardware/omi/tree/d1fdcb4cc4fbd021eac799630e476f144e6bc18a). This is a public summary of source inspection, not a physical-device compatibility certification.

- Stock Omi/DevKit Opus codec IDs **20 and 21**: 16 kHz mono; notification header is little-endian 16-bit sequence plus one-byte fragment ordinal. The following frame-start confirms the preceding frame. An unconfirmed final frame is discarded on stop/disconnect.
- Reassembly and queues are bounded. Sequence gaps/malformed Opus mark loss; recovery must be established by actual decoded PCM, not connection/status/control callbacks.
- Button notifications use the inspected nRF52 eight-byte layout: little-endian event plus zero reserved word. Accept only single/double events; power/long-press remains firmware-owned. Do not replay an initial retained event as a fresh tap.
- Optional brightness requires a valid exact one-byte 0–100 read on the current connection before an explicit write; every write requires readback. OmiGlass reuses the brightness UUID for OTA and returns two bytes; the app rejects that layout before writing. UUID/properties alone are insufficient evidence of safe brightness control.
- Battery/button/brightness support is capability-dependent. Audio must not depend on those optional controls. Zero brightness does not guarantee darkness or persistence across reboot.
- No automatic time sync, firmware update, Wi-Fi provisioning, device-storage recovery, cloud control or wearable audio playback.

Refer to `OmiBle.java`, its host regressions in `tests/omi/`, and the pinned dependency record for implementation. Unknown device/firmware combinations remain unverified.

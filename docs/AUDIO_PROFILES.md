# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

Each remembered Bluetooth output has its own sparse audio profile. A separate **No Bluetooth device** profile covers playback without a Bluetooth output.

Every Equalizer or Effects control is independent:

- **GMMP default** means GoneSmart does not override that value. The current native GMMP value continues to apply.
- **Override** stores a value only for the selected output.
- Resetting a profile removes its overrides; it does not rewrite GMMP's normal settings.

A profile therefore never has to duplicate the complete GMMP DSP configuration just to change one value. For example, a user may override only 32 Hz for headphones or only Bass boost strength for a car while every other setting remains native.

## Covered controls

The initial contract mirrors the current GMMP Equalizer and Effects surfaces:

- Equalizer enable state, preamp and the ten bands from 32 Hz through 16 kHz.
- Balance enable/amount and Force mono.
- Limiter enable, attack and release.
- Bass boost enable/strength.
- Tempo enable/amount and Auto pitch correction.
- Virtualizer enable/strength.
- Amplifier enable/gain.

The companion UI keeps Equalizer and Effects as separate tabs and exposes an Override switch for each individual value.

## Route identity and storage

GoneSmart observes output changes through Android audio-device APIs. Bluetooth routes use a stable opaque identifier derived from device type plus address when Android exposes an address; the device label is presentation metadata only. The no-Bluetooth route has a fixed local identifier.

Audio-profile preferences live in the dedicated `audio_profiles` RemotePreferences group. They are intentionally isolated from the Smart DJ settings group so editing DSP overrides cannot invalidate the recommendation pool.

Stored override keys are sparse and route-scoped:

`override.<route-id>.<setting-id>`

Removing the key means inheritance. Route metadata and passively learned native display values use separate namespaces.

## Native-first application contract

GoneSmart must not implement a parallel equalizer or write guessed GMMP settings. The final runtime path must reuse GMMP's own DSP state/writer/reload boundary so playback behavior stays native.

Until that boundary has been proved for the supported GMMP build, stored overrides fail closed: routes and profile values may be discovered and edited, but GoneSmart does not mutate GMMP audio state.

The development build includes one bounded passive probe to map the GMMP 4.2.1 DSP boundary with a single device-test round. It observes natural SharedPreferences writes while the maintainer changes native EQ/effect controls, records a short non-framework writer call chain for each distinct mutation, and inventories the already-observed EQ-state helper family without invoking candidate methods. String payloads are redacted while repeated String-backed state changes remain distinguishable internally for probe deduplication. The probe is debug-only, time/event bounded, and must be removed once the semantic native mapping is accepted.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and the no-Bluetooth case.
2. An inherited setting must leave GMMP's current native value untouched.
3. An overridden setting must apply through a proved native GMMP boundary.
4. Switching outputs must apply only the sparse differences for the destination profile and restore inheritance for values no longer overridden.
5. Editing GMMP's normal setting while a profile inherits it must remain visible to that profile.
6. The final accepted path must not retain broad preference probes, class inventories or polling.
7. The normal Build workflow must pass on the exact feature-branch head before merge.

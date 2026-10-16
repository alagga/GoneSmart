# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

Each remembered Bluetooth output has its own audio profile. A separate **No Bluetooth device** profile covers playback without a Bluetooth output.

The override granularity mirrors GMMP's UI instead of treating every slider as a separate profile entry:

- **Equalizer** is one complete override. Preset, enabled state, PRE and all bands belong to that one profile section.
- **Effects** are overridden per native GMMP card. A card that contains both an enable switch and one or more sliders is one override, for example Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier.
- Standalone native rows such as Force mono and Auto pitch correction remain standalone overrides.
- Anything without an active override continues to use GMMP's normal value.

Disabling an override returns that section to GMMP inheritance without deleting the stored custom values. Resetting a complete output profile removes the dormant values as well.

## Equalizer

GoneSmart mirrors the current GMMP Equalizer model as one device-specific profile:

- Equalizer enabled state.
- Preset selection. Built-in GMMP names are offered as suggestions and custom preset names can be entered as well.
- `PRE`.
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`PRE` is GMMP's preamp control. The current GMMP help describes the PRE slider as gain applied before the individual bands; it is therefore kept as part of the whole Equalizer profile rather than exposed as an independent override.

Selecting a named preset stores that preset as the profile base. Manually changing PRE or a band changes the GoneSmart profile to `Custom`. Native preset enumeration/application is still required before the feature can mutate playback.

## Effects

The grouped Effects contract is:

- Balance: enabled + amount.
- Force mono: enabled.
- Limiter: enabled + attack + release.
- Bass boost: enabled + strength.
- Tempo: enabled + amount.
- Auto pitch correction: enabled.
- Virtualizer: enabled + strength.
- Amplifier: enabled + gain.

This matches the visual grouping in GMMP and avoids nonsensical partial states such as overriding Bass boost strength while inheriting the Bass boost enabled switch from another context.

## Route identity and storage

GoneSmart observes output changes through Android audio-device APIs both inside the hooked GMMP process and directly from the companion Audio Profiles screen. The direct companion observation is intentional: the currently connected Bluetooth output must be selectable even if the cross-process route notification has not yet reached the companion.

The first development implementation derived route identity from output type plus address. Device testing showed the same physical `SOUNDPEATS Air3 Deluxe HS` output appearing through two different Android audio-device representations within the same connection, which produced two different GoneSmart route IDs. The v2 contract therefore canonicalizes Bluetooth routes primarily by normalized product name and uses type/address only as a fallback when no name exists.

Audio-profile preferences live in the dedicated `audio_profiles_v2` RemotePreferences group. The companion local mirror also uses a v2 store so development data from the old per-field/unstable-route schema does not leak into the new model.

Stored keys are separated into group override markers and field values:

- `override.<route-id>.<group-id>`: the complete section/card is active for this output.
- `value.<route-id>.<field-id>`: stored custom value, active only while its group override marker exists.

Route metadata and passively learned native display values use separate namespaces.

## Device-test evidence from GMMP 4.2.1

The first bundled diagnostic run proved several useful native preference identities without GoneSmart mutating GMMP state:

- Equalizer enabled: `player_eqEnabled`.
- Active Equalizer preset: `dspSettings_activePreset`.
- Balance: `dspSettings_balanceEnabled` + `dspSettings_balance`.
- Force mono: `player_forceMono`.
- Limiter: `dspSettings_limiterEnabled`, `dspSettings_attack`, `dspSettings_release`.
- Bass boost: `dspSettings_bassBoostEnabled`, `dspSettings_bassBoostStrength`.
- Tempo: `player_tempoEnabled`, `player_tempo`.
- Auto pitch correction: `player_autoPitchCorrection`.
- Virtualizer: `dspSettings_virtualizerEnabled`, `dspSettings_virtualizerStrength`.
- Amplifier: `dspSettings_loudnessEnabled`, `dspSettings_loudnessGain`.

The same run showed preset changes such as Club, Soft, Flat and Live and repeatedly logged `r61` saving the current Equalizer state. Preference identity alone is not sufficient proof of the native runtime mutation/reload boundary, so GoneSmart still fails closed for actual DSP application.

## Native-first application contract

GoneSmart must not implement a parallel equalizer or write guessed GMMP settings. The final runtime path must reuse GMMP's own DSP state/writer/reload boundary so playback behavior stays native.

Until that boundary has been proved for the supported GMMP build, stored overrides fail closed: routes and profile values may be discovered and edited, but GoneSmart does not mutate GMMP audio state.

The development build keeps a bounded passive probe for the still-unresolved boundary. The next useful evidence is the natural `r61` call sequence while PRE/bands/presets are changed and the semantic update path that makes an Effects preference change live in `DspManager`. The probe must be removed once those writers are accepted.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and the no-Bluetooth case.
2. The same physical Bluetooth output must not fragment into separate profiles merely because Android reports multiple Bluetooth audio-device types.
3. Equalizer inheritance must leave the complete native GMMP Equalizer untouched.
4. Equalizer override must apply the complete profile through a proved native GMMP boundary, including preset/PRE/bands.
5. Each Effects override must apply its complete native card as one unit.
6. Switching outputs must restore inheritance for groups no longer overridden on the destination profile.
7. Editing GMMP's normal setting while a profile inherits it must remain visible to that profile.
8. The final accepted path must not retain broad preference probes, class inventories or polling.
9. The normal Build workflow must pass on the exact feature-branch head before merge.

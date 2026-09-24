# call-forwarder: full project notes (abandoned 2026-09-24)

Goal: when the Android phone gets a cellular call, let the user answer it on an iPhone that has
no cellular service (non-PTA), from anywhere, without rooting the Android.

Decision: **abandoned.** Replaced by a plain call *notification* (caller number/contact name)
sent through the existing ntfy pipeline in the sms-forwarder project. Nothing here was needed
for that, and the VPS side was fully removed.

## What was built

- Asterisk 16 (Ubuntu apt) on the Oracle VPS, two PJSIP endpoints (`android`, `iphone`), no outbound
  trunk. Config templates in `server/asterisk/`.
- Android app (`android/`, Kotlin, Linphone SDK 5.4.111, GPL-3.0 licensed SDK):
  - `PhoneStateReceiver` detects RINGING / OFFHOOK / IDLE.
  - `BridgeService` (foreground, microphone type) starts a Linphone core, registers over SIP/TLS,
    rings the iPhone first, and only when the iPhone answers calls `TelecomManager.acceptRingingCall()`,
    forces speakerphone and bridges audio (speaker out / microphone in). Hang-ups propagate both ways.
  - `NtfyNotifier` pushes "Incoming call, open Linphone" so the iPhone can be woken.
  - `ForwardTile` Quick Settings tile toggles forwarding.
- iPhone: stock Linphone app as a "third party SIP account" (no custom iOS app, no Apple Developer account).
- Dialplan retries ringing the iPhone for ~50 s so a call rings as soon as Linphone is opened.

## What worked (verified)

- iPhone registers to Asterisk over SIP/TLS; Android registers and places a SIP call that rings the iPhone.
- ntfy wake-up push reaches the iPhone; a two-way SIP call between the phones carried audio
  (after the fixes below).

## What was never verified (and was the real risk)

- A real incoming cellular call end to end: auto-answer, speaker routing, and above all whether the
  Android 10 microphone returns audio while a cellular call is active (Android may silence
  third-party mic capture during calls). Samsung also blocks direct call-audio capture, so the
  speaker-plus-microphone loop is the only non-root route, with echo and mediocre quality.

## Problems found and their fixes (useful if anyone retries)

1. **ISP filtering SIP.** On the user's home connection, TCP handshakes to the server succeeded but
   packets that looked like SIP were dropped (UDP and plain TCP). A plain-text payload got through,
   the SIP message did not. Fix: SIP over TLS (port 8000, certbot certificate).
2. **Only one TCP-type transport binds** in Asterisk 16 (a second TCP transport silently did not listen).
3. **AOR naming.** Registration failed with `AOR 'iphone' not found for endpoint 'iphone'` because the AOR
   section was named `iphone-aor`. The AOR section must have the same name as the user.
4. **TLS 1.3 stall.** The first REGISTER from Linphone (Android) sat unanswered for ~30 s. Forcing the
   Asterisk process to TLS 1.2 (`OPENSSL_CONF` with `MaxProtocol = TLSv1.2` via a systemd drop-in)
   cut registration to ~2 s. `method=tlsv1_2` in pjsip.conf is only a minimum in this pjproject.
5. **Cloud NAT.** Asterisk advertised its private address (10.0.0.242) in SDP, so no audio flowed even
   though calls connected. Fix: `external_media_address` / `external_signaling_address` = public IP.
6. **Bursty microphone uplink** (126 gaps over 100 ms in 83 s, up to 2 s) caused "buffering" on the iPhone.
   Fix: hold a `WIFI_MODE_FULL_LOW_LATENCY` WifiLock and a partial wake lock during calls.
7. **iOS suspends Linphone** in the background, dropping its TLS connection and registration, so
   Asterisk cannot ring it. Asterisk cannot do iOS push (that needs Linphone's Flexisip). Workaround:
   ntfy push plus a dialplan retry loop.
8. `chan_sip` grabbed UDP 5060 once the PJSIP UDP transport was removed; disable it with
   `noload => chan_sip.so`.
9. Oracle security-list rows created with the source port equal to the destination port
   (e.g. TCP 8000 / 993) effectively block clients; leave the source port range empty.
10. Latency: about 350-700 ms round trip to a London VPS from the user's connection.

## Why it was abandoned

Too many moving parts for an unproven core assumption. It needed iOS to cooperate in the background
(it won't), a hacky speaker/microphone audio loop, an Android 10 microphone that may be silent during
calls, and a GPL SDK. Rooting was ruled out because of banking apps.

## Removed from the infrastructure

Asterisk purged from the VPS, `/etc/asterisk` and its systemd drop-in removed, TLS certificate for
`sip.asdl.website` deleted, nginx vhost removed, ufw rules for 8000/tcp and 10000-10020/udp removed,
experimental app uninstalled from the phone.

Left for the owner to tidy (not automatable from here): the Cloudflare DNS record `sip.asdl.website`,
and the Oracle security-list ingress rules for UDP 5060 (two), UDP 10000-10020 and TCP 8000
(plus TCP 5060 if it was added).

## How to resume

Start from the real-call test with a spare phone calling the Android. If the microphone is silent
during the call, the approach is dead without root. Alternatives not tried: Linphone's own hosted
SIP account (has iOS push, so the iPhone rings while locked), SIP/SRTP media encryption, and a
closer server to cut latency.

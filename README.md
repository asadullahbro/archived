# archive

Abandoned or finished experiments, kept for reference.

## call-forwarder (abandoned 2026-09-24)

Attempt to answer calls arriving on an Android phone from an iPhone with no cellular service,
using Asterisk + Linphone over SIP/TLS and a speakerphone/microphone audio bridge, without rooting.
Dropped in favour of a plain "who is calling" notification sent through ntfy
(now part of [sms-forwarder](https://github.com/asadullahbro/sms-forwarder)).

Everything is in [`call-forwarder/`](call-forwarder/). Start with
[`call-forwarder/docs/NOTES.md`](call-forwarder/docs/NOTES.md) for what was built, what was
verified, the problems found and why it was abandoned. Server config templates are in
`call-forwarder/server/asterisk/` and the Android app source in `call-forwarder/android/`.

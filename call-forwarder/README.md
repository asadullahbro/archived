# call-forwarder

> **Status: abandoned (2026-09-24).** Kept for reference. See [docs/NOTES.md](docs/NOTES.md) for everything that
> was built, what was verified, the problems found and why the project was dropped. The infrastructure
> described below has been removed.

Experimental: relay incoming calls from an Android phone to an iPhone that has
no cellular service (e.g. a non-PTA iPhone), over the internet using SIP.
Companion to [sms-forwarder](https://github.com/asadullahbro/sms-forwarder).

```
cellular call --> Android app (auto-answer, speakerphone, mic capture)
                     --SIP/RTP--> Asterisk (self-hosted) --SIP/RTP--> Linphone on iPhone
```

## Why it works this way

- Android (especially Samsung) blocks third-party apps from capturing the real
  call audio stream without root, so the Android side uses speakerphone + mic.
  Expect some echo/noise; acoustic echo cancellation reduces but won't remove it.
- No custom iOS app: the iPhone uses the existing free
  [Linphone](https://www.linphone.org) app, so no Apple Developer account is needed.
- Bluetooth isn't viable: short range, and iOS doesn't let third-party apps open
  classic Bluetooth sockets to other devices.

## Status

- [x] Asterisk server config (`server/asterisk/`)
- [x] Linphone on iPhone registers over SIP/TLS (port 8000)
- [ ] Android app: detect/answer call, force speaker, SIP bridge (Linphone SDK)
- [ ] End-to-end test

## Server setup

Install Asterisk (`apt install asterisk`), copy `server/asterisk/pjsip.conf.example`
to `/etc/asterisk/pjsip.conf` with real passwords, copy `extensions.conf`, set the
RTP range in `rtp.conf` to `rtpstart=10000` / `rtpend=10020`, then open **UDP 5060**
and **UDP 10000-10020** in both the OS firewall and your cloud provider's network
rules. The SIP hostname must be a DNS-only (not proxied) record.

There is no outbound trunk, so the dialplan can only ring the two extensions.

**Use SIP over TLS.** Some ISPs (confirmed on a Pakistani home connection) let the TCP
handshake through but silently drop packets whose payload looks like SIP, on both UDP
and plain TCP. Encrypting the signalling with TLS avoids this. The example config
binds a TLS transport on port 8000 (only one TCP-type transport binds in Asterisk 16);
get a certificate for the SIP hostname (e.g. certbot webroot) and copy it to
`/etc/asterisk/keys/`. In Linphone use transport **TLS** and domain `sip.asdl.website:8000`.
Note that the registration AOR sections must be named exactly like the endpoint
(`[iphone]`), otherwise registration fails with `AOR not found`.

**Behind cloud NAT (e.g. Oracle Cloud):** set `external_media_address` /
`external_signaling_address` to the public IP on the transports, otherwise Asterisk advertises
its private address in SDP and no audio flows even though calls connect. Also force TLS 1.2
for the Asterisk process (`OPENSSL_CONF` with `MaxProtocol = TLSv1.2` via a systemd drop-in):
with TLS 1.3 the first REGISTER from Linphone stalled for ~30 seconds.

**iOS background limit:** Linphone is suspended when not in the foreground, so the Android app
sends an ntfy push ("Incoming call, open Linphone") and the dialplan retries ringing the iPhone
for ~50 seconds until it registers.

## Security notes

SIP/RTP are currently unencrypted (UDP). Use strong passwords, keep the dialplan
free of external trunks, and consider TLS/SRTP before relying on this.

## License

MIT, see [LICENSE](LICENSE).

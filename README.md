# DeX Receiver

Android Wi-Fi Display / Miracast receiver feasibility project.

**Current status: protocol groundwork only — not yet a Miracast sink and not yet compatible with Samsung DeX.** The app currently has a basic network diagnostic prototype. It does not advertise a WFD sink, negotiate a complete RTSP/WFD session, receive RTP media, decode H.264/AAC, handle HDCP, or render DeX.

## Development roadmap

1. **Platform capability / discovery** — determine whether target OEM firmware can expose a Wi-Fi Display sink without privileged permissions. Wi-Fi Direct alone is not sufficient.
2. **RTSP control protocol** — bounded RTSP message parsing and state machine, followed by WFD parameter negotiation.
3. **Media transport** — RTP/UDP or negotiated TCP transport, packet reordering and MPEG-TS depacketization as required by the negotiated profile.
4. **Decode and render** — MediaCodec H.264 video and supported audio decoding, timestamps and surface lifecycle.
5. **Samsung DeX interoperability** — test actual discovery, connection, video and audio from a Galaxy device.

## Architecture milestone

`wfd/WfdRtspParser.kt` introduces a bounded RTSP/1.0 request parser. It validates request lines, headers, content length and incomplete messages. It is intentionally not presented as a working WFD receiver: parsing RTSP is only one part of Miracast, and the Android public SDK does not provide a portable public API to register an application as a Wi-Fi Display sink.

## Build

GitHub Actions builds the debug APK with JDK 17 and Gradle 8.9. See the Actions tab for the latest artifact.

## Device target

Initial target: Lenovo Tab M10 3rd Gen (TB328FU, Android 12), no root. A Galaxy S24 is the source device. The implementation must remain explicit about platform limitations; no fake connected state or simulated video is acceptable.

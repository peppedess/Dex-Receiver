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

## Optional Shizuku integration

The app includes the Shizuku API (12.1.0), a connection/authorization status check, and a best-effort attempt to attach WifiP2pWfdInfo to the Wi-Fi Direct group configuration on Android 11+. Shizuku itself is not root, but it must be installed and started by the user through Android Wireless debugging (Android 11+), then permission must be granted to DeX Receiver.

Important: Shizuku authorization does not automatically grant the app the signature/privileged CONFIGURE_WIFI_DISPLAY permission, nor does it guarantee that the OEM Wi-Fi stack accepts the WFD information element. The current integration is a compatibility probe, not a verified privilege bypass. The app still does not implement the complete RTSP M1–M7 negotiation, RTP media receive/depacketization, or video decoding; it must not be described as a working DeX receiver yet.

## Build

GitHub Actions builds the debug APK with JDK 17 and Gradle 8.9. See the Actions tab for the latest artifact.

## Device target

Initial target: Lenovo Tab M10 3rd Gen (TB328FU, Android 12), no root. A Galaxy S24 is the source device. The implementation must remain explicit about platform limitations; no fake connected state or simulated video is acceptable.

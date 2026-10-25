# DeX Receiver

Android Wi-Fi Display / Miracast receiver prototype targeting the rooted Xiaomi Mi 10T Pro (apollo/apollon).

**Status: experimental protocol prototype, not yet verified as a Samsung DeX-compatible sink.** The current branch contains an RTSP TCP control endpoint, a limited UDP/RTP + MPEG-TS receive path, PAT/PMT H.264 PID discovery, PES extraction, Annex-B access-unit assembly and a MediaCodec surface decoder. These pieces are connected, but complete Wi-Fi Display negotiation and end-to-end interoperability are not yet established.

## Implemented in this milestone

- Wi-Fi Direct group creation and peer diagnostics.
- Best-effort WFD information attached to WifiP2pConfig through hidden API reflection.
- RTSP TCP listener on port 7236 with bounded message parsing.
- Limited RTSP OPTIONS / GET_PARAMETER / SET_PARAMETER / SETUP / PLAY / TEARDOWN state handling.
- UDP RTP listener on port 19000.
- RTP validation and MPEG-TS 188-byte packet parsing.
- PAT/PMT parsing to locate an H.264 elementary stream.
- PES payload extraction and Annex-B H.264 access-unit assembly.
- Android MediaCodec AVC decoder connected to an on-screen SurfaceView.
- Root and Shizuku diagnostics.

## Known limitations — important

This is not yet a complete Miracast/WFD sink and may not appear in Samsung DeX's wireless display list. In particular:

1. Full WFD M1–M7 capability negotiation is not implemented. The app does not yet advertise and negotiate a complete wfd_video_formats, audio codec, EDID, content-protection and presentation profile.
2. The selected UDP transport is a narrow prototype; RTCP handling, packet reordering/loss recovery and all interoperability variants are not implemented.
3. Audio decoding/rendering is not implemented.
4. The decoder currently starts with a fixed 1920×1080 AVC format; negotiated resolution, codec profile, timing and dynamic format changes are not handled.
5. HDCP-protected sessions are not supported.
6. Android's public SDK does not expose a portable API to register an application as a Wi-Fi Display sink. Root does not automatically grant signature-only framework permissions such as CONFIGURE_WIFI_DISPLAY; the target ROM's Wi-Fi stack may still refuse to advertise this app as a sink.

A successful APK build proves compilation only. It does not prove that the phone is discoverable by DeX or that a complete stream can be received. Do not treat an RTSP connection or a running decoder as proof of Miracast compatibility.

## Device and build

- Target device: Xiaomi Mi 10T Pro, codename apollo / ROM flavor apollon.
- Android 17, arm64-v8a, Magisk root.
- JDK 17, Gradle 8.9, Android Gradle Plugin 8.5.2.
- GitHub Actions publishes the debug APK as DeX-Receiver-debug-apk.

## Next engineering work

- Implement and validate complete WFD parameter negotiation and source/sink capability matching.
- Add negotiated RTP/RTCP transport, sequence handling and timestamp handling.
- Add audio stream discovery and LPCM/AAC playback.
- Use negotiated H.264 dimensions/profile and robust decoder reconfiguration.
- Verify WFD advertisement and session establishment on the actual target ROM, then test Samsung DeX end to end.

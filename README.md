# DeX Receiver

Android Wi-Fi Display / Miracast receiver feasibility prototype.

**Status:** early diagnostic prototype, not a complete Samsung DeX receiver. It does not yet implement full RTSP/WFD negotiation, RTP media transport, H.264/AAC decoding, HDCP, or Samsung DeX interoperability.

The goal of this repository is to validate whether the target Android firmware exposes WFD sink capability to a normal third-party app without root.


Build note: commit `c7fba27` removes unavailable hidden WFD APIs so the diagnostic app can compile against the public Android SDK. It still does not advertise a Miracast sink or receive DeX video.

<div align="center">
  
# 🌐 KamuiNet: Decentralized P2P Tunneling
### *Secure, Private, and Borderless Connectivity*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-SDK_34-green.svg)](https://developer.android.com/)
[![WebRTC](https://img.shields.io/badge/WebRTC-Enabled-blue.svg)](https://webrtc.org/)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)]()

---

**KamuiNet** is an advanced P2P tunneling ecosystem designed to break geographical and network boundaries. By leveraging **WebRTC** for real-time peer-to-peer data streaming and an integrated **VPN Service**, KamuiNet creates private, encrypted tunnels directly between devices, eliminating the need for vulnerable centralized servers.

---

</div>

## 🚀 Key Features

*   **P2P Tunneling**: High-performance data routing between peers using WebRTC.
*   **Encrypted VPN Service**: System-wide secure tunnel for local network sharing.
*   **Real-time Connectivity**: Instant handshaking via custom signaling architecture.
*   **Privacy-First Design**: Zero-trust approach to data routing.
*   **Barcode Integration**: Seamless device pairing via built-in QR Scanner.

---

## 🛠 Technical Architecture

KamuiNet is built on a modern, reactive stack designed for stability and low-latency performance:

| Component | Technology |
| :--- | :--- |
| **Language** | Kotlin (Coroutines, Flow) |
| **P2P Engine** | WebRTC |
| **Networking** | Ktor, OkHttp |
| **Backend** | Supabase (Auth, Realtime, Functions) |
| **DI** | Hilt |
| **Persistence** | Room Database |
| **Security** | Android Security Crypto Library |

---

## 🏗 Project Structure

```text
com.kamuinet.client
├── di/             # Dependency Injection (Hilt)
├── vpn/            # KamuiVpnService (System-level tunnel)
├── webrtc/         # P2P connection & stream management
├── signaling/      # Real-time connection handshake logic
├── router/         # Traffic routing & KamuiHostService
├── supabase/       # Backend integration
└── ui/             # Jetpack Compose / View interfaces
```

---

## 🛡 Security & Privacy

KamuiNet operates on the principle that your data belongs to you. By tunneling directly between devices:
1.  **Reduced Attack Surface**: No centralized proxy server to compromise.
2.  **Encryption**: End-to-end encryption for all tunneled traffic.
3.  **Local Control**: User-initiated connections via cryptographically verified pairing.

---

<div align="center">
  
*Built for the future of decentralized networking.*

</div>

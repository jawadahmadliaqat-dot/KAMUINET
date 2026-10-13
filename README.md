# KamuiNet — Secure Peer-to-Peer Internet Sharing

KamuiNet is a production-grade Android application that enables users to share their mobile data or Wi-Fi connection with other devices through a secure, encrypted P2P tunnel. Built with a focus on reliability, privacy, and ease of use, it utilizes high-performance networking stacks to ensure a seamless experience even on restricted networks.

---

## 🛠️ Technology Stack & Development Environment

### Core Language & Build System
*   **Kotlin 2.0.21**: Leveraging the K2 compiler, Coroutines, and Kotlinx Serialization.
*   **Gradle 8.5+**: Build system managed by Kotlin DSL (`.gradle.kts`).
*   **Version Catalogs**: Centralized dependency management via `libs.versions.toml`.
*   **KSP (Kotlin Symbol Processing)**: Used for high-performance code generation for Hilt and Room.
*   **Android SDK**: Compiled with SDK 34, Min SDK 26 (Android 8.0).

### 🛡️ Networking & P2P Tunneling
*   **Stream WebRTC Android (1.1.2)**: High-performance WebRTC implementation for encrypted, low-latency P2P binary transport.
*   **VpnService**: Native Android API for userspace IP capture and packet injection via a virtual interface.
*   **Custom Framing Protocol**: A binary protocol encapsulating TCP/UDP flows into frames (`OPEN`, `DATA`, `CLOSE`).
*   **Ktor 3.0.3**: Modern, asynchronous networking client for API calls and backend interaction.
*   **OkHttp 4.12.0**: Underlying engine for efficient HTTP/HTTPS communications.

### ☁️ Backend & Infrastructure (Supabase 3.0.3)
KamuiNet uses **Supabase** as a comprehensive backend-as-a-service:
*   **Auth (auth-kt)**: Secure user management, including **Email/Password** and **Google One Tap Sign-In**.
*   **Postgrest (postgrest-kt)**: RESTful API for interacting with the PostgreSQL database.
*   **Realtime (realtime-kt)**: WebSocket-based Postgres Change Data Capture (CDC) for P2P signaling (SDP/ICE exchange).
*   **Functions (functions-kt)**: Serverless Edge Functions for dynamic TURN credential generation.
*   **PostgreSQL**: Relational database storing host registration and session metadata.

### 🏗️ Architecture & Core Libraries
*   **Hilt (Dagger 2.51.1)**: Dependency Injection for a modular, maintainable, and testable codebase.
*   **Room Persistence (2.6.1)**: Local SQLite database for session history and preferences.
*   **Jetpack Lifecycle (2.8.1)**: ViewModel and LiveData for robust state management.
*   **Jetpack Navigation (2.7.7)**: Type-safe navigation between Fragments using Navigation Graphs.
*   **AppCompat & Material Components (1.12.0)**: Modern UI components following Material 3 guidelines.

### 🔍 Identity & Security
*   **Android Credentials Manager (1.2.2)**: Unified sign-in interface for Google and password-based accounts.
*   **Google Play Services Auth**: Integrated for seamless Google ID login.
*   **CameraX (1.3.4)**: Modern Android camera API used for QR code capture.
*   **Google ML Kit (18.3.0)**: Barcode scanning API for fast and accurate QR code decoding.
*   **ZXing Android Embedded (4.3.0)**: Utility library for QR code generation.
*   **SecurityUtils**: Custom implementation for Root Detection and Emulator detection.

---

## 📁 Project Structure

The project follows a feature-layered MVVM architecture:

*   `com.kamuinet.client.di`: Hilt modules for dependency injection.
*   `com.kamuinet.client.data`: Repository implementations, Room Entities, and DAOs.
*   `com.kamuinet.client.ui`: UI Fragments, ViewModels, and ViewHolders.
*   `com.kamuinet.client.vpn`: VPN Service implementation, TUN engine, and packet handling.
*   `com.kamuinet.client.webrtc`: WebRTC PeerConnection management and DataChannel logic.
*   `com.kamuinet.client.signaling`: Supabase Realtime-based P2P signaling layer.
*   `com.kamuinet.client.supabase`: Supabase client initialization and Auth management.
*   `com.kamuinet.client.router`: Routing logic for host-side traffic forwarding.
*   `com.kamuinet.client.util`: Shared utilities (Loggers, Security checks, Network helpers).

---

## 🚀 Key Features

*   **One-Click Sharing**: Instantly turn your device into an internet host.
*   **Secure Pairing**: Connect via **QR Code**, **Peer ID**, or **Quick Connect**.
*   **Hardened VPN Tunnel**: System-wide VPN interface captures traffic and relays it securely to the host.
*   **Proxy-over-WebRTC**: Advanced framing protocol ensuring 100% standard TCP/UDP behavior.
*   **Live Security Audit**: Real-time telemetry showing data secured (MBs) and tunnel health.
*   **Google One Tap**: Frictionless authentication experience.

---

## 🏗️ Architecture Detail: "The Gold Standard Tunnel"

KamuiNet moves beyond simple proxies by implementing a **Protect + Proxy** model:

1.  **Socket Protection**: Uses `VpnService.protect()` to ensure signaling and WebRTC traffic bypass the tunnel, preventing routing loops.
2.  **Framing Protocol**: Binary protocol encapsulates TCP/UDP flows into frames.
3.  **Local SYN/ACK**: `TunProxyEngine` performs local handshakes, reducing perceived latency.
4.  **Unordered Delivery**: WebRTC DataChannels are configured to be **Unordered**, preventing Head-of-Line (HoL) blocking.

---

## 📋 Setup & Deployment

### Prerequisites
*   Android Studio Ladybug+
*   A Supabase Project
*   A TURN provider (e.g., Metered.ca)

### Configuration
1.  **Environment Variables**: Add `SUPABASE_URL` and `SUPABASE_KEY` to your `local.properties` file.
2.  **Database**: Execute `schema.sql` (found in the root directory) in the Supabase SQL Editor.
3.  **Edge Functions**: Deploy the `turn-credentials` function to handle ICE server generation.
4.  **Google Auth**: Configure your Web Client ID in the Google Cloud Console and add it to `res/values/strings.xml` as `default_web_client_id`.

---
*Developed with ❤️ for the Android Community.*

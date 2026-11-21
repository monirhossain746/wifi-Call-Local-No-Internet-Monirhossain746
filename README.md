# Wifi Call Local

Offline-first local WiFi, Phone Hotspot, and WiFi Direct messaging, voice/video calling, and chunked file sharing for Android — built with **Java 11**, **XML Layouts**, **ViewBinding**, **Material Components 3**, **Room**, **NanoHTTPD / WebSocket**, **WebRTC**, **ZXing**, **Glide**, **Gson**, **WorkManager**, and **Google AdMob + UMP**.

**Package ID:** `com.android.wificalllocal`  
**Minimum SDK:** 26 (Android 8.0)  
**Target / Compile SDK:** 36 (Android 16)  
**Developer:** Md. Monir Hossain (Mymensingh, Bangladesh) — [Portfolio](https://monirhossain746.blogspot.com)

---

## 1. Architecture & Core Features

- **Zero-Internet Local Mesh Networking:**
  - **Discovery:** mDNS (`NsdManager`), UDP Subnet Broadcast + `MulticastLock`, Phone Hotspot (`LocalOnlyHotspot`), WiFi Direct (`WifiP2pManager`), QR Code scanning/generation, and manual IP:Port entry.
  - **Transport:** Embedded `NanoWSD` (`NanoHTTPD` + `nanohttpd-websocket`) server + `Java-WebSocket` client mesh with 5-second heartbeats, 15-second offline timeout, and auto-reconnection.
  - **Sync & Ordering:** Hybrid Logical Clock (`HybridLogicalClock`) for causal ordering + deduplication by `messageId`.
- **Rich Local Messaging & Groups:**
  - 1-to-1 DMs, default **Everyone Room**, Public Groups, and PIN-protected Private Groups.
  - Typing indicators, Sent/Delivered/Seen receipts, replies, forwarding, emoji reactions with "Who reacted", voice notes (`MediaRecorder`/`MediaPlayer`), Delete for Me, Delete for Everyone.
  - **Group Security:** PBKDF2WithHmacSHA256 (65,536 iterations, per-group salt) + HMAC-SHA256 challenge-response (raw PIN is never sent over the network) + AES-256-GCM group payload encryption + 5-attempt lockout with exponential backoff.
- **Local WebRTC Voice & Video Calling:**
  - `io.github.webrtc-sdk:android` using direct **Host ICE candidates** (zero external STUN/TURN servers).
  - Full-screen call UI with mute, speakerphone, camera toggle, camera flip, Picture-in-Picture (`CallActivity`), ringtone/vibration, and lockscreen wake support (`CallForegroundService`).
- **Chunked Integrity-Verified File Sharing:**
  - 1 MB chunk transfers via WorkManager (`FileTransferWorker`), per-chunk + full-file SHA-256 verification, multi-source chunk downloading, pause/resume/cancel/retry.
  - Saved without overwriting into `Downloads/WifiCallLocal/{Images, Videos, Audio, Documents, Others}` using zero-permission `MediaStore.Downloads` on Android 10+.
- **Built-in Local Web Client & Versioned Protocol (`v1`):**
  - Open `http://<device-ip>:<port>` from any browser on the same WiFi/Hotspot to join the Everyone Room, public groups, or 1-to-1 peer chats (`dm:<minId>_<maxId>`), view image thumbnails + zoomable preview lightbox, upload/download SHA-256 verified files, react/reply/delete messages, and manage call signaling.
  - All peer and browser messages share a versioned JSON envelope (`version`, `type`, `event`, `messageId`, `senderId`, `timestamp`, `payload`) backward-compatible with flat packets (`HELLO`, `HEARTBEAT`, `CHAT_MESSAGE`, `MESSAGE_RECEIPT`, `MESSAGE_REACTION`, `MESSAGE_DELETE`, `TYPING`, `CALL_OFFER`, `CALL_ANSWER`, `CALL_ICE`, `CALL_RINGING`, `CALL_BUSY`, `CALL_END`, `WEB_SNAPSHOT`).
  - **Browser WebRTC HTTPS Note:** Real bidirectional WebRTC calling is wired via `RTCPeerConnection` (`iceServers: []`, Host ICE), gated honestly on `window.isSecureContext && navigator.mediaDevices?.getUserMedia`. Plain HTTP LAN URLs (`http://192.168.x.x:8080`) display a clear browser compatibility warning rather than simulating calls; serve behind a local HTTPS reverse proxy (e.g. Caddy/mkcert) or use the native Android app for LAN calls.
- **Image Preview & Structured File Cards:**
  - Inline image thumbnails cached via `thumb.jpg` / `thumbnailBase64` open a full-screen zoomable multi-image `ViewPager2` preview (`ZoomableImageView`: pinch-to-zoom, double-tap zoom, Close, and Download).
  - File cards separate **Open** (opens downloaded file via `ACTION_VIEW` + `FileProvider`; displays *"Please download this file before opening it."* when not downloaded without silently starting a download) from **Download** (saves to `Downloads/WifiCallLocal/` with pause/resume/cancel/retry and duplicate-download prevention).
- **Offline-Safe AdMob Adaptive Banner & Consent:**
  - Uses responsive anchored Adaptive Banner sizing (`AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize`) with AdMob App ID (`@string/admob_app_id`) and Banner Unit ID (`@string/admob_adaptive_banner_unit_id`) externalized in `app/src/main/res/values/strings.xml`, hidden cleanly offline, during calls, inside chat detail screens, and while the keyboard is open.
- **Firebase Remote Config Kill Switch & Force Update (`wifi-call-local-1`):**
  - Integrated `RemoteConfigManager`, `AppControlManager`, `UpdateManager`, and non-cancelable `AppBlockedActivity` with offline `SharedPreferences` caching:
    - `is_app_enabled` (`boolean`): When `false`, immediately blocks app access/calls/messaging with *"অ্যাপটি বর্তমানে বন্ধ রাখা হয়েছে / This application is currently disabled."* and restores access when re-enabled.
    - `latest_version_code` (`long`), `update_link` (`https://` APK URL), `update_message` (`string`): Enforces non-cancelable update prompt when `latest_version_code > BuildConfig.VERSION_CODE`, downloads via `DownloadManager`, verifies package name + higher version + matching signing certificate before launching the system installer.
- **Languages & Themes:** English + Bangla (`bn`), Dark Navy / Light / System Default themes.

---

## 2. Build, Firebase & AdMob Setup Instructions

### Build Debug APK
```bash
gradle assembleDebug
```
Output APK:
- `app/build/outputs/apk/debug/app-debug.apk`
- Also mirrored identically to `.build-outputs/app-debug.apk` and `APK_DOWNLOAD/app-debug.apk`.

### Run Unit Tests
```bash
gradle :app:testDebugUnitTest
```

### Enable Live Firebase Remote Config (`wifi-call-local-1`)
1. In the Firebase Console for project **`wifi-call-local-1`**, register an **Android app** with package name **`com.android.wificalllocal`**. *(Web `firebaseConfig` objects alone cannot configure the native Android Firebase SDK.)*
2. Download `google-services.json` and place it at **`app/google-services.json`**.
3. Create Remote Config parameters in Firebase Console:
   - `is_app_enabled` (`Boolean`, default `true`)
   - `latest_version_code` (`Number`, default `1`)
   - `update_link` (`String`, HTTPS `.apk` URL)
   - `update_message` (`String`)
4. Until `app/google-services.json` is added, `MissingGoogleServicesStrategy.WARN` allows building and running safely with offline defaults (`app/src/main/res/xml/remote_config_defaults.xml`) + cached state.

### Configure Production AdMob IDs
Replace `admob_app_id` and `admob_adaptive_banner_unit_id` in `app/src/main/res/values/strings.xml` with your real production AdMob App ID and Adaptive Banner Ad Unit ID before release.

### Verify APK Signature & Manifest
```bash
$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2 dump badging APK_DOWNLOAD/app-debug.apk
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --verbose APK_DOWNLOAD/app-debug.apk
```

### Signed Release Build (Play Store `.aab` / `.apk`)
1. Generate an upload keystore (`my-upload-key.jks`) using `keytool`.
2. Export environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) — never commit passwords or keystores to Git.
3. Update `app/google-services.json` and production AdMob IDs in `strings.xml`.
4. Enable `isMinifyEnabled = true` if distributing an obfuscated release AAB (`gradle bundleRelease`). ProGuard keep rules for WebRTC, NanoHTTPD, Java-WebSocket, Room, Gson, Glide, ZXing, Firebase Remote Config, and AdMob/UMP are configured in `app/proguard-rules.pro`.

---

## 3. Google Play Store Listing Metadata

### App Name
`Wifi Call Local` (15 characters)

### Short Description (English, ≤ 80 chars)
`Chat, call and share files over local WiFi — no internet or login needed.` *(73 chars)*

### Short Description (Bangla, ≤ 80 chars)
`ইন্টারনেট বা লগইন ছাড়াই লোকাল WiFi-তে চ্যাট, কল ও ফাইল শেয়ার করুন।` *(67 chars)*

### Full Description (English)
**Wifi Call Local** is a fast, private offline communication app that lets nearby people chat, make voice & video calls, and share large files directly over your local **WiFi router, Phone Hotspot, or WiFi Direct** — with zero internet connection, zero phone numbers, and zero accounts.

**Key Features:**
• **Works Without Internet:** Communicate across classrooms, offices, homes, campuses, trips, or emergency zones with no mobile data or central servers.
• **Automatic Local Discovery:** Discovers nearby peers automatically via mDNS, subnet broadcast, Phone Hotspot (`LocalOnlyHotspot`), WiFi Direct, QR code scanning, or manual IP connection.
• **HD Local Voice & Video Calls:** Peer-to-peer WebRTC audio and video calls with Picture-in-Picture, mute, speakerphone, camera switch, and call history.
• **Public & Private Groups:** Join the Everyone Room immediately, create topic groups, or lock private groups with a salted PBKDF2 + HMAC challenge-response PIN and AES-256-GCM encryption.
• **Reliable Chunked File Transfer:** Send photos, videos, audio, voice notes, and documents in 1 MB SHA-256 verified chunks with pause, resume, and automatic saving to `Downloads/WifiCallLocal/`.
• **Browser Web Client:** Any laptop or phone browser on the same WiFi/Hotspot can scan the QR code and chat or exchange files immediately without installing the app.
• **Bilingual & Customizable:** Full English and Bangla (`বাংলা`) support with Dark Navy, Light, and System themes.

### Full Description (Bangla / বাংলা)
**Wifi Call Local** হলো একটি সম্পূর্ণ অফলাইন লোকাল কমিউনিকেশন অ্যাপ। কোনো ইন্টারনেট, সিম কার্ড, ফোন নম্বর বা অ্যাকাউন্ট ছাড়াই একই **WiFi রাউটার, মোবাইল হটস্পট বা WiFi Direct**-এর মাধ্যমে কাছাকাছি ডিভাইসগুলোর সাথে চ্যাট, ভয়েস/ভিডিও কল এবং ফাইল শেয়ার করুন।

**মূল বৈশিষ্ট্যসমূহ:**
• **ইন্টারনেট ছাড়াই সম্পূর্ণ কার্যকর:** অফিস, ক্লাসরুম, বাড়ি, ভ্রমণ বা জরুরি অবস্থায় মোবাইল ডেটা ছাড়াই সরাসরি যোগাযোগ করুন।
• **অটোমেটিক লোকাল ডিসকভারি:** mDNS, সাবনেট ব্রডকাস্ট, হটস্পট, WiFi Direct, QR কোড অথবা ম্যানুয়াল IP দিয়ে সহজেই যুক্ত হোন।
• **লোকাল ভয়েস ও ভিডিও কল:** সরাসরি পিয়ার-টু-পিয়ার WebRTC কল, স্পিকার/মিউট, ক্যামেরা ফ্লিপ ও পিকচার-ইন-পিকচার সুবিধা।
• **পাবলিক ও প্রাইভেট গ্রুপ:** সবার জন্য উন্মুক্ত Everyone Room ছাড়াও নিরাপদ PIN ও AES-256-GCM এনক্রিপ্টেড প্রাইভেট গ্রুপ তৈরি করুন।
• **নিরাপদ ফাইল শেয়ারিং:** ছবি, ভিডিও, অডিও, ভয়েস নোট ও ডকুমেন্ট ১ MB চাংক ও SHA-256 যাচাইকরণসহ সরাসরি `Downloads/WifiCallLocal/` ফোল্ডারে সংরক্ষণ হয়।
• **ব্রাউজার ওয়েব ক্লায়েন্ট:** অ্যাপ ইনস্টল না করেও একই WiFi-এর যেকোনো ব্রাউজারে QR কোড স্ক্যান করে চ্যাট ও ফাইল আদান-প্রদান করা যায়।

---

## 4. Play Policy, Privacy Policy & Data Safety

### Privacy Policy Summary
- **Local-First Data:** All profiles, peer lists, messages, call logs, and group memberships reside solely in on-device Room SQLite storage (`wifi_call_local.db`) and `Downloads/WifiCallLocal/`.
- **No Central Server / No Account:** No email, phone number, password, or contact book sync is ever collected or uploaded.
- **Advertising Disclosure (AdMob + UMP):** Banner ads load strictly when validated internet connectivity exists. When online, Google Mobile Ads SDK and User Messaging Platform (UMP) collect advertising-related identifiers/diagnostics according to the user's consent selection. The app remains 100% usable offline.

### Data Safety Form Reference
- **Data collected by Developer:** None (No developer backend).
- **Third-party SDK (only when internet is present):** Google Mobile Ads SDK / UMP (`Device or other IDs`, `App interactions`, `Diagnostics` for Advertising & Consent compliance).
- **Security Practices:** Data encrypted in local private group sessions (AES-256-GCM + salted PBKDF2 HMAC challenge-response). All user-authored messages can be deleted at any time.

### Permission Justifications
| Permission | Purpose |
|---|---|
| `NEARBY_WIFI_DEVICES` (API 33+) / `ACCESS_FINE_LOCATION` (API 26–32) | Required by Android OS for WiFi Direct (`WifiP2pManager`), LocalOnlyHotspot, and mDNS local peer discovery (`neverForLocation` asserted on API 33+). |
| `INTERNET` & `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | Local HTTP/WebSocket socket binding (`NanoWSD`), UDP mDNS/broadcast packets, and conditional AdMob loading when internet is active. |
| `CAMERA` | Optional QR code scanning, video calling, and profile camera capture. |
| `RECORD_AUDIO` | Voice calls, video call audio, and voice notes. |
| `POST_NOTIFICATIONS` (API 33+) | Incoming calls, local messages, active foreground mesh status, and download progress. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `SPECIAL_USE` / `MICROPHONE` / `CAMERA` | Maintaining the local peer server and active calls reliably in background. |
| `VIBRATE`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK` | Ringing and waking screen for incoming local WebRTC calls. |
| **Zero-Permission Photo Picker & MediaStore** | No `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `READ_EXTERNAL_STORAGE` requested. Uses Android Photo Picker (`PickVisualMedia`), `OpenDocument`, `MediaStore.Downloads`, and `ContactsContract.Intents.Insert`. |

### Content Rating & Store Assets Checklist
- **Content Rating:** Communication / Utility — Everyone (User-to-user local interaction occurs strictly on the user's own LAN; offline local groups require explicit join or PIN).
- **Store Assets Checklist:**
  - App Icon: `512 x 512 px` PNG (generated from `ic_launcher_art`)
  - Feature Graphic: `1024 x 500 px` PNG (deep navy `#091830` with cyan/emerald mesh highlights)
  - Phone Screenshots (4–8 images): Home Node Card, Chats/Everyone Room, Dark Call Screen, QR Join Web Browser View, Settings & About Developer

---

## 5. Manual Testing Guide

1. **Same WiFi Router Test:** Install `app-debug.apk` on Device A & Device B connected to the same WiFi router (AP isolation disabled). Complete onboarding -> both appear under **Nearby Users** automatically via mDNS/UDP -> tap **Chat**, send text, emoji reaction, voice note, and a 5 MB file -> verify file appears under `Downloads/WifiCallLocal/` with matching SHA-256 -> tap **Voice** / **Video** to verify WebRTC call + PiP.
2. **Phone Hotspot Test (No Router):** Enable Android Mobile Hotspot on Device A (or **Host Hotspot** in Nearby) and connect Device B to Device A's hotspot -> tap **Scan Nearby** or scan Device A's **QR Join** code -> verify full chat, file transfer, and call work completely offline.
3. **Browser Web Client Test:** On a laptop or third phone connected to the same WiFi/Hotspot, scan Device A's QR code or navigate to `http://<device-a-ip>:8080` -> join **Everyone Room**, send a message and upload a file -> verify Android peers see the `Web` badge and receive messages/files in real time.
4. **Private Group PIN Security Test:** Create a PIN-protected private group -> attempt wrong PIN 5 times from Device B -> verify 60s lockout countdown -> wait/reset and join with correct PIN -> verify encrypted group chat works without raw PIN transmission.

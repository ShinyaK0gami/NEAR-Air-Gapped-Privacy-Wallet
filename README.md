# NearAir ("AirNEAR") — Air-Gapped Zero-Hardware Cold Storage for NEAR Protocol

**NearAir** is an open-source, zero-cost, strictly air-gapped cold storage signing system for the **NEAR Protocol**. It turns any spare offline Android smartphone into a hardware-backed Ed25519 cryptographic signing enclave (`cold-storage`) that communicates with an online companion wallet (`public-wallet` / web client) strictly via optical QR codes.

### 🌐 Live Web Apps (Production on Vercel):
* **[Near Wallet Public (Companion Watcher)](https://watcher-bay-xi.vercel.app/)** — Connects to NEAR RPC, checks balances, runs 5-signal risk analysis, builds transactions, and broadcasts signatures.
* **[NEAR Cold Storage (S1 Hardware Vault Simulator)](https://watcher-bay-xi.vercel.app/vault)** — Air-gapped vault interface with offline 12-word seed generation, optical QR scanner, PIN authorization keypad, and Ed25519 signing.

### 📱 Android APKs:
* **[cold-storage-debug.apk](file:///d:/NEAR%20Protocol%20Project/apps/vault/app/build/outputs/apk/debug/cold-storage-debug.apk)** — Offline Android Hardware Cold Storage with ARM TrustZone TEE protection.
* **[public-wallet-debug.apk](file:///d:/NEAR%20Protocol%20Project/apps/vault/public-wallet/build/outputs/apk/debug/public-wallet-debug.apk)** — Online companion wallet with Phantom dark glass UI.

---

## Repository Structure

```text
nearair/
├── apps/
│   ├── watcher/               # Online Next.js 14 Web Client (TypeScript, TailwindCSS, near-api-js, ZXing, qrcode.react)
│   │   ├── src/
│   │   │   ├── components/    # BalanceCard.tsx, TxBuilder.tsx, QRDisplay.tsx, QRScanner.tsx
│   │   │   ├── lib/           # borsh.ts, payload.ts, near.ts
│   │   │   ├── pages/         # index.tsx, _app.tsx, _document.tsx
│   │   │   └── __tests__/     # protocol.test.ts (Borsh & Ed25519 cross-platform tests)
│   │   └── package.json
│   └── vault/                 # Offline Native Android App (Kotlin, Jetpack Compose, CameraX, ZXing, Keystore)
│       ├── app/
│       │   ├── src/main/
│       │   │   ├── AndroidManifest.xml
│       │   │   └── java/com/nearair/vault/
│       │   │       ├── MainActivity.kt
│       │   │       ├── crypto/     # KeystoreManager.kt, Signer.kt
│       │   │       ├── model/      # TxPayload.kt (Borsh codec + JSON envelope security verifier)
│       │   │       └── ui/         # CameraScanScreen.kt, TxConfirmScreen.kt, QROutputScreen.kt
│       │   └── src/test/java/com/nearair/vault/
│       │       └── VaultCryptoAndBorshTest.kt
│       └── build.gradle.kts
└── docs/
    └── PROTOCOL.md            # Optical QR Payload & Borsh Binary Serialization Specification
```

---

## Security Guarantees

1. **Zero Network Permissions in Vault**: `apps/vault/app/src/main/AndroidManifest.xml` declares **only** `android.permission.CAMERA`. Both `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` are completely absent and verified by automated unit tests and runtime checks.
2. **Borsh Binary Integrity Verification**: When `nearair-vault` scans an unsigned QR payload, it independently deserializes the raw `borshSerialized` bytes (`Transaction`) and asserts that `signerId`, `receiverId`, `publicKey`, `nonce`, `blockHash`, and action parameters match the JSON envelope before displaying the confirmation screen.
3. **Hardware-Backed Key Storage**: Ed25519 private keys are encrypted at rest using `EncryptedSharedPreferences` (`AES256_GCM`) backed by the Android Hardware Keystore (`MasterKey`).
4. **Animated Multi-Frame QR Pagination**: Oversized transactions (>850 bytes, such as complex `FunctionCall` proposals) automatically split into cyclic `near_airgap_frame` QR chunks with deterministic checksum verification.

---

## Quick Start

### 1. Online Watcher (`apps/watcher`)
```bash
cd apps/watcher
npm install
npm test
npm run dev
```
Open `http://localhost:3000` to watch NEAR Testnet/Mainnet accounts, construct unsigned `Transfer`, `FunctionCall`, or `AddKey` transactions, render optical QR codes, and broadcast signed transactions to `https://rpc.testnet.near.org`.

### 2. Air-Gapped Android Vault (`apps/vault`)
```bash
cd apps/vault
gradle testDebugUnitTest assembleDebug
```
Install `apps/vault/app/build/outputs/apk/debug/app-debug.apk` onto an offline Android device (or use the built-in Vault Simulator inside `nearair-watcher` for instant desktop testing).


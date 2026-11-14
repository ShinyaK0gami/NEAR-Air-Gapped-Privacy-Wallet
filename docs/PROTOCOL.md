# NearAir ("AirNEAR") Optical Communication & Serialization Protocol (v1.0)

## 1. Overview

**NearAir** is a zero-cost, strictly air-gapped cold storage signing protocol for the **NEAR Protocol**. It decouples transaction construction and network broadcasting (**`watcher`**) from cryptographic key storage and signing (**`vault`**) using an optical QR channel.

```
┌──────────────────────────────┐     Step A: Unsigned TX QR      ┌──────────────────────────────┐
│       nearair-watcher        │ ──────────────────────────────► │        nearair-vault         │
│     (Online Web Client)      │                                 │   (Air-Gapped Android App)   │
│                              │     Step B: Signed TX QR        │                              │
│  • Queries NEAR Testnet RPC  │ ◄────────────────────────────── │  • Zero Network Permissions  │
│  • Builds Unsigned Borsh TX  │                                 │  • Android Keystore Ed25519  │
│  • Broadcasts Signed Borsh   │     Init: Vault Public Key QR   │  • Verifies Raw Borsh Bytes  │
│    TX via RPC                │ ◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │  • Signs SHA-256(Borsh TX)   │
└──────────────────────────────┘                                 └──────────────────────────────┘
```

---

## 2. Cryptographic & Serialization Standards

| Parameter | Standard | Details |
| :--- | :--- | :--- |
| **Elliptic Curve** | `Ed25519` | NEAR native curve (`keyType = 0`). Public keys prefixed with `ed25519:<base58>`. |
| **Transaction Hashing** | `SHA-256` | Signatures are computed over the 32-byte `SHA-256` digest of the raw unsigned Borsh-serialized `Transaction` bytes. |
| **Binary Serialization** | `Borsh` | Binary Object Representation Serializer for Hashing (deterministic little-endian binary format). |
| **Transport Encoding** | `Base64` + `JSON` | Raw Borsh bytes are Base64-encoded (RFC 4648) inside a UTF-8 JSON envelope rendered as an ISO/IEC 18004 QR Code (Error Correction Level `M` or `Q`). |

---

## 3. NEAR Borsh Binary Schema

Both `watcher` (TypeScript / `near-api-js`) and `vault` (Kotlin / `NearBorshDeserializer`) implement identical binary schemas:

### 3.1 `PublicKey`
| Field | Borsh Type | Size | Description |
| :--- | :--- | :--- | :--- |
| `keyType` | `u8` | 1 byte | `0` = `ED25519`, `1` = `SECP256K1` |
| `data` | `[u8; 32]` | 32 bytes | Raw 32-byte Ed25519 public key |

### 3.2 `Transaction` (Unsigned)
| Order | Field | Borsh Type | Description |
| :--- | :--- | :--- | :--- |
| 1 | `signerId` | `string` | `u32` (LE length) + UTF-8 bytes of signer account ID |
| 2 | `publicKey` | `PublicKey` | Signer's `PublicKey` (33 bytes for Ed25519) |
| 3 | `nonce` | `u64` | 8-byte little-endian unsigned integer (`current_nonce + 1`) |
| 4 | `receiverId` | `string` | `u32` (LE length) + UTF-8 bytes of receiver account ID |
| 5 | `blockHash` | `[u8; 32]` | 32 raw bytes of a recent block hash on the target NEAR network |
| 6 | `actions` | `Vec<Action>` | `u32` (LE action count) followed by serialized `Action` variants |

### 3.3 `Action` Variants
Each `Action` is prefixed with a 1-byte `u8` variant index:

- **`0` — `CreateAccount`**: No payload bytes.
- **`1` — `DeployContract`**: `code: Vec<u8>` (`u32` length + raw WASM bytes).
- **`2` — `FunctionCall`**:
  - `methodName: string` (`u32` length + UTF-8 bytes)
  - `args: Vec<u8>` (`u32` length + raw argument bytes)
  - `gas: u64` (8-byte little-endian unsigned integer, in gas units)
  - `deposit: u128` (16-byte little-endian unsigned integer, in yoctoNEAR)
- **`3` — `Transfer`**:
  - `deposit: u128` (16-byte little-endian unsigned integer, in yoctoNEAR, where $1\text{ NEAR} = 10^{24}\text{ yoctoNEAR}$)
- **`4` — `Stake`**:
  - `stake: u128` (16-byte little-endian)
  - `publicKey: PublicKey` (33 bytes)
- **`5` — `AddKey`**:
  - `publicKey: PublicKey` (33 bytes)
  - `accessKey: AccessKey`:
    - `nonce: u64` (8-byte little-endian)
    - `permission: AccessKeyPermission` (`u8` enum discriminant):
      - `0` (`FunctionCallPermission`):
        - `allowance: Option<u128>` (`u8` `0` for `None`, or `1` followed by 16-byte LE `u128`)
        - `receiverId: string` (`u32` length + UTF-8 bytes)
        - `methodNames: Vec<string>` (`u32` count + Borsh strings)
      - `1` (`FullAccess`): No additional fields.
- **`6` — `DeleteKey`**: `publicKey: PublicKey` (33 bytes).
- **`7` — `DeleteAccount`**: `beneficiaryId: string`.

### 3.4 `SignedTransaction`
| Order | Field | Borsh Type | Description |
| :--- | :--- | :--- | :--- |
| 1 | `transaction` | `Transaction` | Exact raw Borsh bytes of the unsigned `Transaction` |
| 2 | `signature` | `Signature` | `u8` keyType (`0` for Ed25519) + `[u8; 64]` raw Ed25519 signature bytes |

**Signing Formula:**
$$\text{txHash} = \text{SHA-256}(\text{borsh}(\text{Transaction}))$$
$$\text{sig} = \text{Ed25519.sign}(\text{txHash}, \text{secretKey})$$
$$\text{borsh}(\text{SignedTransaction}) = \text{borsh}(\text{Transaction}) \parallel \text{0x00} \parallel \text{sig}$$

---

## 4. Optical QR Payload Envelopes

### 4.0 Vault Public Key Pairing (Vault -> Watcher)
Used during initial setup to share the air-gapped Vault's Ed25519 public key with the online Watcher without typing:

```json
{
  "protocol": "near_airgap",
  "version": "1.0",
  "networkId": "testnet",
  "action": "pair_vault",
  "data": {
    "publicKey": "ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp",
    "implicitAccountId": "4d7f8b1c9e2a..."
  }
}
```

### 4.1 Step A: Unsigned Transaction Payload (Watcher -> Vault)
The Watcher constructs the unsigned NEAR transaction and encodes both the canonical `near_airgap` v1.0 schema and flat compatibility fields:

```json
{
  "protocol": "near_airgap",
  "version": "1.0",
  "networkId": "testnet",
  "action": "sign_tx",
  "type": "sign_request",
  "signerId": "alice.testnet",
  "receiverId": "bob.testnet",
  "amount": "1.5",
  "unsignedTxBase64": "BASE64_ENCODED_RAW_UNSIGNED_BORSH_TX",
  "data": {
    "signerId": "alice.testnet",
    "publicKey": "ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp",
    "nonce": 104523,
    "receiverId": "bob.testnet",
    "blockHash": "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
    "actions": [
      {
        "type": "Transfer",
        "params": {
          "deposit": "1500000000000000000000000"
        }
      }
    ],
    "borshSerialized": "BASE64_ENCODED_RAW_UNSIGNED_BORSH_TX"
  }
}
```

#### Vault Mandatory Verification Rules
Upon scanning Step A, `nearair-vault` **MUST**:
1. Decode `data.borshSerialized` (or `unsignedTxBase64`) from Base64 into raw bytes.
2. Independently deserialize the raw Borsh binary into `Transaction` fields (`signerId`, `publicKey`, `nonce`, `receiverId`, `blockHash`, `actions`).
3. Assert that the deserialized binary fields strictly equal the JSON envelope's human-readable fields (`signerId`, `receiverId`, `nonce`, `publicKey`, and action parameters).
4. Display ONLY the verified Borsh-deserialized parameters on the confirmation screen. If any field mismatches, abort and display a tamper warning.

### 4.2 Step B: Signed Transaction Response (Vault -> Watcher)
After user approval on the offline device, the Vault signs `SHA-256(unsignedBorshBytes)` and displays:

```json
{
  "protocol": "near_airgap",
  "version": "1.0",
  "networkId": "testnet",
  "action": "broadcast_tx",
  "type": "sign_response",
  "signerId": "alice.testnet",
  "receiverId": "bob.testnet",
  "signature": "BASE64_ED25519_SIGNATURE",
  "signedTxBase64": "BASE64_ENCODED_RAW_SIGNED_BORSH_TX",
  "data": {
    "signerId": "alice.testnet",
    "receiverId": "bob.testnet",
    "txHash": "BASE58_SHA256_TX_HASH",
    "signature": "BASE64_ED25519_SIGNATURE",
    "signatureBase58": "ed25519:BASE58_ED25519_SIGNATURE",
    "signedBorshTx": "BASE64_ENCODED_RAW_SIGNED_BORSH_TX"
  }
}
```

---

## 5. Multi-Frame Animated QR Chunking Protocol

When a JSON payload exceeds the safe single-QR optical density threshold (**850 bytes**, e.g. `FunctionCall` transactions with large JSON arguments), both `watcher` and `vault` automatically segment the UTF-8 JSON string into cyclic animated QR frames:

```json
{
  "protocol": "near_airgap_frame",
  "version": "1.0",
  "payloadId": "8f3a1b2c",
  "index": 0,
  "total": 3,
  "checksum": "a94f1c02",
  "chunk": "eyJwcm90b2NvbCI6Im5lYXJfYWlyZ2FwIi..."
}
```

- **`payloadId`**: First 8 hex characters of `SHA-256(fullPayloadJson)`.
- **`index`**: Zero-based frame index (`0 .. total - 1`).
- **`total`**: Total number of frames required to reconstruct the payload.
- **`checksum`**: First 8 hex characters of `SHA-256(fullPayloadJson)` verified after concatenating all `chunk` segments in index order.
- **`chunk`**: Base64 or raw slice of the full JSON string for frame `index`.


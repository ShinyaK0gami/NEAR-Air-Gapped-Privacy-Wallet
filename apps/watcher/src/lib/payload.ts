/**
 * NearAir Optical QR Payload Envelope & Multi-Frame Chunking Library
 *
 * Supports both:
 * - Canonical `near_airgap` v1.0 nested `data` envelope
 * - Flat `nearair` v1.0 `sign_request` / `sign_response` fields
 * - Automatic multi-frame animated QR pagination (`near_airgap_frame`) for large payloads
 */

import nacl from "tweetnacl";
import {
  base64ToBytes,
  bytesToBase64,
  bytesToHex,
  decodeBase58,
  deserializeNearTransaction,
  deserializeSignedNearTransaction,
  encodeBase58,
  formatNearPublicKey,
  type NearActionDescriptor,
  type NearTransactionDescriptor,
  parseNearPublicKey,
  serializeNearTransaction,
  serializeSignedNearTransaction,
  sha256Bytes,
  yoctoToNear,
} from "./borsh.ts";

export const MAX_SINGLE_QR_CHARS = 1100;
export const TARGET_FRAME_CHUNK_CHARS = 320;

export interface UnsignedTxEnvelope {
  protocol: "near_airgap" | "nearair";
  version: "1.0";
  networkId: "testnet" | "mainnet";
  action: "sign_tx";
  type: "sign_request";
  signerId: string;
  receiverId: string;
  amount: string; // Human-readable NEAR amount summary
  unsignedTxBase64: string;
  data: {
    signerId: string;
    publicKey: string;
    nonce: number;
    receiverId: string;
    blockHash: string;
    actions: NearActionDescriptor[];
    borshSerialized: string;
  };
}

export interface SignedTxEnvelope {
  protocol: "near_airgap" | "nearair";
  version: "1.0";
  networkId: "testnet" | "mainnet";
  action: "broadcast_tx";
  type: "sign_response";
  signerId: string;
  receiverId: string;
  signature: string; // base64 64-byte signature
  signedTxBase64: string;
  data: {
    signerId: string;
    receiverId: string;
    txHash: string; // base58 SHA-256 hash of unsigned Borsh bytes
    signature: string; // base64 64-byte signature
    signatureBase58: string; // ed25519:<base58>
    signedBorshTx: string; // base64 SignedTransaction Borsh bytes
  };
}

export interface VaultPairingEnvelope {
  protocol: "near_airgap" | "nearair";
  version: "1.0";
  networkId: "testnet" | "mainnet";
  action: "pair_vault";
  accountId?: string;
  publicKey?: string;
  data: {
    accountId?: string;
    publicKey: string;
    implicitAccountId: string;
    label?: string;
  };
}

export interface QrFrameChunk {
  protocol: "near_airgap_frame";
  version: "1.0";
  payloadId: string;
  index: number;
  total: number;
  checksum: string;
  chunk: string;
}

/**
 * Compute human-readable NEAR deposit summary across actions
 */
export function summarizeNearAmount(actions: NearActionDescriptor[]): string {
  let totalYocto = 0n;
  for (const act of actions) {
    if (act.type === "Transfer") {
      totalYocto += BigInt(act.params.deposit);
    } else if (act.type === "FunctionCall") {
      totalYocto += BigInt(act.params.deposit || "0");
    }
  }
  return yoctoToNear(totalYocto, 6);
}

/**
 * Create an Unsigned Transaction QR Envelope from a NearTransactionDescriptor
 */
export function createUnsignedTxEnvelope(
  tx: NearTransactionDescriptor,
  networkId: "testnet" | "mainnet" = "testnet"
): {
  envelope: UnsignedTxEnvelope;
  jsonString: string;
  unsignedBorshBytes: Uint8Array;
} {
  const unsignedBorshBytes = serializeNearTransaction(tx);
  const borshBase64 = bytesToBase64(unsignedBorshBytes);
  const amount = summarizeNearAmount(tx.actions);

  const envelope: UnsignedTxEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId,
    action: "sign_tx",
    type: "sign_request",
    signerId: tx.signerId,
    receiverId: tx.receiverId,
    amount,
    unsignedTxBase64: borshBase64,
    data: {
      signerId: tx.signerId,
      publicKey: tx.publicKey.startsWith("ed25519:")
        ? tx.publicKey
        : `ed25519:${tx.publicKey}`,
      nonce: Number(tx.nonce),
      receiverId: tx.receiverId,
      blockHash: tx.blockHash,
      actions: tx.actions,
      borshSerialized: borshBase64,
    },
  };

  // Compact optical QR JSON string (~320 chars) so it always fits in a single, low-density QR code
  // that phone cameras scan instantaneously while still carrying full Borsh binary + verification fields.
  const compactQrPayload = {
    protocol: "near_airgap",
    version: "1.0",
    networkId,
    action: "sign_tx",
    data: {
      signerId: tx.signerId,
      receiverId: tx.receiverId,
      nonce: Number(tx.nonce),
      borshSerialized: borshBase64,
    },
  };

  return {
    envelope,
    jsonString: JSON.stringify(compactQrPayload),
    unsignedBorshBytes,
  };
}

/**
 * Parse and strictly verify an UnsignedTxEnvelope against its embedded Borsh bytes
 */
export function verifyAndParseUnsignedEnvelope(rawJson: string): {
  envelope: UnsignedTxEnvelope;
  deserializedTx: NearTransactionDescriptor;
  unsignedBorshBytes: Uint8Array;
} {
  const parsed = JSON.parse(rawJson);
  if (
    parsed.protocol !== "near_airgap" &&
    parsed.protocol !== "nearair"
  ) {
    throw new Error(`Unsupported QR protocol: "${parsed.protocol}"`);
  }

  const borshBase64: string | undefined =
    parsed.data?.borshSerialized || parsed.unsignedTxBase64;
  if (!borshBase64) {
    throw new Error("Missing Borsh serialized payload in QR envelope");
  }

  const unsignedBorshBytes = base64ToBytes(borshBase64);
  const deserializedTx = deserializeNearTransaction(unsignedBorshBytes);

  // Security Assertion: Compare envelope fields with Borsh-deserialized fields
  const envSigner = parsed.data?.signerId ?? parsed.signerId;
  const envReceiver = parsed.data?.receiverId ?? parsed.receiverId;
  const envNonce = parsed.data?.nonce;
  const envPubKey = parsed.data?.publicKey;

  if (envSigner && envSigner !== deserializedTx.signerId) {
    throw new Error(
      `SECURITY ALERT: Envelope signerId ("${envSigner}") does not match Borsh binary signerId ("${deserializedTx.signerId}")`
    );
  }
  if (envReceiver && envReceiver !== deserializedTx.receiverId) {
    throw new Error(
      `SECURITY ALERT: Envelope receiverId ("${envReceiver}") does not match Borsh binary receiverId ("${deserializedTx.receiverId}")`
    );
  }
  if (envNonce !== undefined && Number(envNonce) !== Number(deserializedTx.nonce)) {
    throw new Error(
      `SECURITY ALERT: Envelope nonce (${envNonce}) does not match Borsh binary nonce (${deserializedTx.nonce})`
    );
  }
  if (envPubKey) {
    const normalizedEnvKey = envPubKey.startsWith("ed25519:")
      ? envPubKey
      : `ed25519:${envPubKey}`;
    if (normalizedEnvKey !== deserializedTx.publicKey) {
      throw new Error(
        `SECURITY ALERT: Envelope publicKey ("${normalizedEnvKey}") does not match Borsh binary publicKey ("${deserializedTx.publicKey}")`
      );
    }
  }

  const normalizedEnvelope: UnsignedTxEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId: parsed.networkId === "mainnet" ? "mainnet" : "testnet",
    action: "sign_tx",
    type: "sign_request",
    signerId: deserializedTx.signerId,
    receiverId: deserializedTx.receiverId,
    amount: summarizeNearAmount(deserializedTx.actions),
    unsignedTxBase64: borshBase64,
    data: {
      signerId: deserializedTx.signerId,
      publicKey: deserializedTx.publicKey,
      nonce: Number(deserializedTx.nonce),
      receiverId: deserializedTx.receiverId,
      blockHash: deserializedTx.blockHash,
      actions: deserializedTx.actions,
      borshSerialized: borshBase64,
    },
  };

  return {
    envelope: normalizedEnvelope,
    deserializedTx,
    unsignedBorshBytes,
  };
}

/**
 * Parse and cryptographically verify a Signed Transaction QR Response from the Vault
 */
export async function parseAndVerifySignedEnvelope(rawJson: string): Promise<{
  envelope: SignedTxEnvelope;
  signedBorshBytes: Uint8Array;
  deserialized: ReturnType<typeof deserializeSignedNearTransaction>;
  txHashBase58: string;
  txHashHex: string;
  signatureVerified: boolean;
}> {
  const parsed = JSON.parse(rawJson);
  if (
    parsed.protocol !== "near_airgap" &&
    parsed.protocol !== "nearair"
  ) {
    throw new Error(`Invalid signed QR protocol: "${parsed.protocol}"`);
  }

  const signedBorshBase64: string | undefined =
    parsed.data?.signedBorshTx ||
    parsed.signedTxBase64 ||
    parsed.signedBorshTx;

  if (!signedBorshBase64) {
    throw new Error("Signed QR payload is missing `signedBorshTx` / `signedTxBase64`");
  }

  const signedBorshBytes = base64ToBytes(signedBorshBase64);
  const deserialized = deserializeSignedNearTransaction(signedBorshBytes);

  // Compute SHA-256 of the unsigned Borsh portion
  const txHashBytes = await sha256Bytes(deserialized.unsignedBorshBytes);
  const txHashBase58 = encodeBase58(txHashBytes);
  const txHashHex = bytesToHex(txHashBytes);

  // Verify Ed25519 signature against the transaction's publicKey
  const { data: pubKeyBytes } = parseNearPublicKey(deserialized.transaction.publicKey);
  const signatureVerified = nacl.sign.detached.verify(
    txHashBytes,
    deserialized.signatureBytes,
    pubKeyBytes
  );

  if (!signatureVerified) {
    throw new Error(
      "Cryptographic verification failed: Ed25519 signature does not match SHA-256(unsignedTransaction) and signer PublicKey"
    );
  }

  const normalized: SignedTxEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId: parsed.networkId === "mainnet" ? "mainnet" : "testnet",
    action: "broadcast_tx",
    type: "sign_response",
    signerId: deserialized.transaction.signerId,
    receiverId: deserialized.transaction.receiverId,
    signature: deserialized.signatureBase64,
    signedTxBase64: signedBorshBase64,
    data: {
      signerId: deserialized.transaction.signerId,
      receiverId: deserialized.transaction.receiverId,
      txHash: txHashBase58,
      signature: deserialized.signatureBase64,
      signatureBase58: deserialized.signatureBase58,
      signedBorshTx: signedBorshBase64,
    },
  };

  return {
    envelope: normalized,
    signedBorshBytes,
    deserialized,
    txHashBase58,
    txHashHex,
    signatureVerified,
  };
}

/**
 * Desktop PC (No Webcam) Helper:
 * Combines the `unsignedBorshBytes` already held by the PC Watch-Only Wallet with
 * a detached `ed25519:<base58>` (or Base64) signature from the offline phone app.
 */
export async function combineUnsignedTxWithDetachedSignature(
  unsignedBorshBytes: Uint8Array,
  signatureInput: string,
  networkId: "testnet" | "mainnet" = "testnet"
): Promise<ReturnType<typeof parseAndVerifySignedEnvelope>> {
  const trimmed = signatureInput.trim();

  // If the user pasted a full JSON envelope, delegate to parseAndVerifySignedEnvelope
  if (trimmed.startsWith("{")) {
    return parseAndVerifySignedEnvelope(trimmed);
  }

  let signatureBytes: Uint8Array;
  if (trimmed.startsWith("ed25519:")) {
    signatureBytes = decodeBase58(trimmed.slice("ed25519:".length).trim());
  } else {
    // Try Base58 first (if 80-90 chars), otherwise Base64
    try {
      const b58 = decodeBase58(trimmed);
      if (b58.length === 64) {
        signatureBytes = b58;
      } else {
        signatureBytes = base64ToBytes(trimmed);
      }
    } catch {
      signatureBytes = base64ToBytes(trimmed);
    }
  }

  if (signatureBytes.length !== 64) {
    throw new Error(
      `Invalid Ed25519 signature length: expected 64 bytes, got ${signatureBytes.length}`
    );
  }

  const signedBorshBytes = serializeSignedNearTransaction(
    unsignedBorshBytes,
    signatureBytes
  );
  const signedBorshBase64 = bytesToBase64(signedBorshBytes);
  const deserialized = deserializeSignedNearTransaction(signedBorshBytes);

  const syntheticEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId,
    action: "broadcast_tx",
    data: {
      signerId: deserialized.transaction.signerId,
      receiverId: deserialized.transaction.receiverId,
      signedBorshTx: signedBorshBase64,
    },
  };

  return parseAndVerifySignedEnvelope(JSON.stringify(syntheticEnvelope));
}

/**
 * Offline Vault Signing implementation (also used for test vectors & in-browser Vault simulation)
 */
export async function signUnsignedEnvelopeOffline(
  unsignedQrJson: string,
  secretKeySeedOrFull64: Uint8Array
): Promise<{
  signedEnvelope: SignedTxEnvelope;
  signedJson: string;
  signedBorshBytes: Uint8Array;
  txHashBase58: string;
  approvalCode6Digit: string;
}> {
  const { envelope, deserializedTx, unsignedBorshBytes } =
    verifyAndParseUnsignedEnvelope(unsignedQrJson);

  const keyPair =
    secretKeySeedOrFull64.length === 32
      ? nacl.sign.keyPair.fromSeed(secretKeySeedOrFull64)
      : nacl.sign.keyPair.fromSecretKey(secretKeySeedOrFull64);

  const derivedPubKey = formatNearPublicKey(keyPair.publicKey);
  if (derivedPubKey !== deserializedTx.publicKey) {
    console.warn(
      `Vault key notice: Transaction specifies ${deserializedTx.publicKey}, but Vault will sign with ${derivedPubKey}`
    );
  }

  const txHashBytes = await sha256Bytes(unsignedBorshBytes);
  const txHashBase58 = encodeBase58(txHashBytes);
  const signatureBytes = nacl.sign.detached(txHashBytes, keyPair.secretKey);
  const signedBorshBytes = serializeSignedNearTransaction(
    unsignedBorshBytes,
    signatureBytes
  );

  const signatureBase64 = bytesToBase64(signatureBytes);
  const signatureBase58 = `ed25519:${encodeBase58(signatureBytes)}`;
  const signedBorshBase64 = bytesToBase64(signedBorshBytes);

  const signedEnvelope: SignedTxEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId: envelope.networkId,
    action: "broadcast_tx",
    type: "sign_response",
    signerId: deserializedTx.signerId,
    receiverId: deserializedTx.receiverId,
    signature: signatureBase64,
    signedTxBase64: signedBorshBase64,
    data: {
      signerId: deserializedTx.signerId,
      receiverId: deserializedTx.receiverId,
      txHash: txHashBase58,
      signature: signatureBase64,
      signatureBase58,
      signedBorshTx: signedBorshBase64,
    },
  };

  return {
    signedEnvelope,
    signedJson: JSON.stringify(signedEnvelope),
    signedBorshBytes,
    txHashBase58,
    approvalCode6Digit: computeSixDigitApprovalCode(signatureBytes),
  };
}

/**
 * Compute deterministic 6-Digit Hardware Approval Code from the 64-byte Ed25519 signature.
 * Matches Kotlin `Signer.signTransactionBorsh` in `nearair-vault` byte-for-byte.
 */
export function computeSixDigitApprovalCode(signatureBytes: Uint8Array): string {
  const u32 =
    ((signatureBytes[0] & 0xff) * 16777216 +
      ((signatureBytes[1] & 0xff) << 16) +
      ((signatureBytes[2] & 0xff) << 8) +
      (signatureBytes[3] & 0xff)) >>>
    0;
  return String(u32 % 1_000_000).padStart(6, "0");
}

/**
 * Standard BIP-39 Mnemonic to 64-byte Seed using Web Crypto PBKDF2
 */
export async function bip39MnemonicToSeed(
  mnemonic: string,
  passphrase = ""
): Promise<Uint8Array> {
  const normalizedMnemonic = mnemonic
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .join(" ");
  const salt = `mnemonic${passphrase}`;

  const enc = new TextEncoder();
  const passKey = await crypto.subtle.importKey(
    "raw",
    enc.encode(normalizedMnemonic),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );

  const derivedBits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt: enc.encode(salt),
      iterations: 2048,
      hash: "SHA-512",
    },
    passKey,
    512
  );

  return new Uint8Array(derivedBits);
}

/**
 * Standard SLIP-0010 Ed25519 Key Derivation for NEAR (m/44'/397'/0')
 */
export async function slip0010DeriveNear(
  seed64: Uint8Array
): Promise<{ key: Uint8Array; chainCode: Uint8Array }> {
  const enc = new TextEncoder();
  const masterKey = await crypto.subtle.importKey(
    "raw",
    enc.encode("ed25519 seed"),
    { name: "HMAC", hash: "SHA-512" },
    false,
    ["sign"]
  );
  const I = new Uint8Array(
    await crypto.subtle.sign("HMAC", masterKey, seed64 as unknown as BufferSource)
  );
  let key = I.slice(0, 32);
  let chainCode = I.slice(32, 64);

  // Path: m/44'/397'/0'
  const segments = [44, 397, 0];
  const HARDENED = 0x80000000;

  for (const seg of segments) {
    const index = (seg + HARDENED) >>> 0;
    const indexBuffer = new Uint8Array(4);
    const view = new DataView(indexBuffer.buffer);
    view.setUint32(0, index, false); // Big-Endian

    // data = 0x00 || key (32 bytes) || index (4 bytes)
    const data = new Uint8Array(1 + 32 + 4);
    data[0] = 0x00;
    data.set(key, 1);
    data.set(indexBuffer, 33);

    const childHmacKey = await crypto.subtle.importKey(
      "raw",
      chainCode as unknown as BufferSource,
      { name: "HMAC", hash: "SHA-512" },
      false,
      ["sign"]
    );
    const childI = new Uint8Array(
      await crypto.subtle.sign("HMAC", childHmacKey, data as unknown as BufferSource)
    );
    key = childI.slice(0, 32);
    chainCode = childI.slice(32, 64);
  }

  return { key, chainCode };
}

/**
 * Derive standard NEAR keypair from 12-word BIP-39 mnemonic (SLIP-0010 m/44'/397'/0').
 * Matches SafePal S1, Meteor, HERE, and MyNearWallet byte-for-byte.
 */
export async function deriveNearKeyPairFromMnemonic(
  mnemonic: string,
  passphrase = ""
): Promise<{
  publicKey: string;
  secretKey: string;
  implicitAccountId: string;
  seedBytes: Uint8Array;
}> {
  const seed64 = await bip39MnemonicToSeed(mnemonic, passphrase);
  const { key } = await slip0010DeriveNear(seed64);
  const kp = nacl.sign.keyPair.fromSeed(key);

  return {
    publicKey: formatNearPublicKey(kp.publicKey),
    secretKey: `ed25519:${encodeBase58(kp.secretKey)}`,
    implicitAccountId: bytesToHex(kp.publicKey),
    seedBytes: key,
  };
}

/**
 * Derive a deterministic Ed25519 Vault keypair bound to a specific NEAR account ID.
 * Matches Kotlin `KeystoreManager.createDeterministicWalletForAccount` byte-for-byte.
 */
export async function deriveDeterministicKeyPairForAccount(
  accountId: string
): Promise<{
  publicKey: string;
  implicitAccountId: string;
  seedBytes: Uint8Array;
}> {
  const cleanAccount = accountId.trim().toLowerCase();
  const domainInput = `NEARAIR_TESTNET_VAULT_V1:${cleanAccount}`;
  const seed = await sha256Bytes(new TextEncoder().encode(domainInput));
  const kp = nacl.sign.keyPair.fromSeed(seed);
  return {
    publicKey: formatNearPublicKey(kp.publicKey),
    implicitAccountId: bytesToHex(kp.publicKey),
    seedBytes: seed,
  };
}

/**
 * Derive the exact same Ed25519 keypair as Kotlin `KeystoreManager.importSeedOrSecretKey`
 * from a 12-word recovery phrase, 64-char hex seed, or `ed25519:...` secret key.
 */
export async function deriveVaultKeyPairFromSeedOrPhrase(rawInput: string): Promise<{
  publicKey: string;
  implicitAccountId: string;
  seedBytes: Uint8Array;
}> {
  const trimmed = rawInput.trim();
  const words = trimmed.toLowerCase().split(/\s+/).filter(Boolean);

  // 1. 12 or 24-word recovery phrase (BIP-39 + SLIP-0010)
  if (words.length >= 12) {
    try {
      const kp = await deriveNearKeyPairFromMnemonic(words.join(" "));
      return {
        publicKey: kp.publicKey,
        implicitAccountId: kp.implicitAccountId,
        seedBytes: kp.seedBytes,
      };
    } catch {
      // Fallback if derivation fails
    }
  }

  // 2. ed25519:... format
  if (trimmed.startsWith("ed25519:")) {
    const rawB58 = trimmed.slice("ed25519:".length).trim();
    const decoded = decodeBase58(rawB58);
    if (decoded.length === 64) {
      const kp = nacl.sign.keyPair.fromSecretKey(decoded);
      return {
        publicKey: formatNearPublicKey(kp.publicKey),
        implicitAccountId: bytesToHex(kp.publicKey),
        seedBytes: decoded.slice(0, 32),
      };
    } else if (decoded.length === 32) {
      const kp = nacl.sign.keyPair.fromSeed(decoded);
      return {
        publicKey: formatNearPublicKey(kp.publicKey),
        implicitAccountId: bytesToHex(kp.publicKey),
        seedBytes: decoded,
      };
    }
  }

  // 3. 64-char Hex Seed (32 bytes)
  if (/^[0-9a-fA-F]{64}$/.test(trimmed)) {
    const seed = new Uint8Array(32);
    for (let i = 0; i < 32; i++) {
      seed[i] = parseInt(trimmed.slice(i * 2, i * 2 + 2), 16);
    }
    const kp = nacl.sign.keyPair.fromSeed(seed);
    return {
      publicKey: formatNearPublicKey(kp.publicKey),
      implicitAccountId: bytesToHex(kp.publicKey),
      seedBytes: seed,
    };
  }

  // 4. 128-char Hex Secret Key (64 bytes)
  if (/^[0-9a-fA-F]{128}$/.test(trimmed)) {
    const full = new Uint8Array(64);
    for (let i = 0; i < 64; i++) {
      full[i] = parseInt(trimmed.slice(i * 2, i * 2 + 2), 16);
    }
    const kp = nacl.sign.keyPair.fromSecretKey(full);
    return {
      publicKey: formatNearPublicKey(kp.publicKey),
      implicitAccountId: bytesToHex(kp.publicKey),
      seedBytes: full.slice(0, 32),
    };
  }

  // 5. Raw Base58 secret key (without ed25519: prefix)
  try {
    const decoded = decodeBase58(trimmed);
    if (decoded.length === 64) {
      const kp = nacl.sign.keyPair.fromSecretKey(decoded);
      return {
        publicKey: formatNearPublicKey(kp.publicKey),
        implicitAccountId: bytesToHex(kp.publicKey),
        seedBytes: decoded.slice(0, 32),
      };
    } else if (decoded.length === 32) {
      const kp = nacl.sign.keyPair.fromSeed(decoded);
      return {
        publicKey: formatNearPublicKey(kp.publicKey),
        implicitAccountId: bytesToHex(kp.publicKey),
        seedBytes: decoded,
      };
    }
  } catch {
    // not base58
  }

  // 6. Fallback: SHA-256 of UTF-8 text
  const utf8 = new TextEncoder().encode(trimmed);
  const seed = await sha256Bytes(utf8);
  const kp = nacl.sign.keyPair.fromSeed(seed);
  return {
    publicKey: formatNearPublicKey(kp.publicKey),
    implicitAccountId: bytesToHex(kp.publicKey),
    seedBytes: seed,
  };
}

/**
 * Parse a Vault Public Key Pairing QR code (`pair_vault`) or raw `ed25519:...` string
 */
export function parseVaultPairingPayload(rawInput: string): {
  accountId: string;
  publicKey: string;
  implicitAccountId: string;
  networkId: "testnet" | "mainnet";
} {
  const trimmed = rawInput.trim();
  if (trimmed.startsWith("ed25519:") || (!trimmed.startsWith("{") && trimmed.length >= 40)) {
    const { data } = parseNearPublicKey(trimmed);
    const implicitId = bytesToHex(data);
    return {
      accountId: implicitId,
      publicKey: formatNearPublicKey(data),
      implicitAccountId: implicitId,
      networkId: "testnet",
    };
  }

  const parsed = JSON.parse(trimmed);
  const pubKeyStr = parsed.data?.publicKey || parsed.publicKey;
  if (!pubKeyStr) {
    throw new Error("QR payload does not contain a NEAR `publicKey`");
  }
  const { data } = parseNearPublicKey(pubKeyStr);
  const implicitAccountId = bytesToHex(data);
  const accountId =
    parsed.data?.accountId ||
    parsed.accountId ||
    implicitAccountId;

  return {
    accountId: String(accountId).trim().toLowerCase(),
    publicKey: formatNearPublicKey(data),
    implicitAccountId,
    networkId: parsed.networkId === "mainnet" ? "mainnet" : "testnet",
  };
}

/**
 * Build a `pair_vault` JSON payload (used by the interactive phone onboarding simulator)
 */
export function createVaultPairingEnvelope(
  publicKey: string,
  accountId: string,
  networkId: "testnet" | "mainnet" = "testnet"
): { envelope: VaultPairingEnvelope; jsonString: string } {
  const { data } = parseNearPublicKey(publicKey);
  const cleanKey = formatNearPublicKey(data);
  const implicitAccountId = bytesToHex(data);
  const cleanAccount = accountId.trim().toLowerCase() || implicitAccountId;

  const envelope: VaultPairingEnvelope = {
    protocol: "near_airgap",
    version: "1.0",
    networkId,
    action: "pair_vault",
    accountId: cleanAccount,
    publicKey: cleanKey,
    data: {
      accountId: cleanAccount,
      publicKey: cleanKey,
      implicitAccountId,
    },
  };

  return {
    envelope,
    jsonString: JSON.stringify(envelope),
  };
}

/**
 * Simple deterministic 8-char hex checksum for multi-frame QR reassembly
 */
export function computeShortChecksum(input: string): string {
  let h1 = 0xdeadbeef ^ input.length;
  let h2 = 0x41c6ce57 ^ input.length;
  for (let i = 0; i < input.length; i++) {
    const ch = input.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  return (h1 >>> 0).toString(16).padStart(8, "0");
}

/**
 * Split a large JSON string into balanced, easy-to-scan animated QR frames if it exceeds `maxSingleQrSize`
 */
export function chunkPayloadToFrames(
  fullJson: string,
  maxSingleQrSize = MAX_SINGLE_QR_CHARS
): string[] {
  if (fullJson.length <= maxSingleQrSize) {
    return [fullJson];
  }

  const checksum = computeShortChecksum(fullJson);
  const payloadId = checksum;
  // Balance frame sizes evenly (~320 chars per frame) so every frame has large, easy-to-scan QR modules
  const total = Math.max(2, Math.ceil(fullJson.length / TARGET_FRAME_CHUNK_CHARS));
  const balancedChunkSize = Math.ceil(fullJson.length / total);
  const frames: string[] = [];

  for (let index = 0; index < total; index++) {
    const chunk = fullJson.slice(
      index * balancedChunkSize,
      (index + 1) * balancedChunkSize
    );
    const frameObj: QrFrameChunk = {
      protocol: "near_airgap_frame",
      version: "1.0",
      payloadId,
      index,
      total,
      checksum,
      chunk,
    };
    frames.push(JSON.stringify(frameObj));
  }

  return frames;
}

/**
 * Multi-Frame QR Reassembler for scanning animated QR codes
 */
export class MultiFrameCollector {
  private payloadId: string | null = null;
  private total = 0;
  private checksum = "";
  private chunks = new Map<number, string>();

  reset(): void {
    this.payloadId = null;
    this.total = 0;
    this.chunks.clear();
    this.checksum = "";
  }

  ingest(rawScannedText: string): {
    complete: boolean;
    fullPayload?: string;
    receivedCount: number;
    totalCount: number;
  } {
    const trimmed = rawScannedText.trim();
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed.protocol === "near_airgap_frame") {
        const frame = parsed as QrFrameChunk;
        if (this.payloadId !== frame.payloadId || this.total !== frame.total) {
          this.payloadId = frame.payloadId;
          this.total = frame.total;
          this.checksum = frame.checksum;
          this.chunks.clear();
        }
        this.chunks.set(frame.index, frame.chunk);

        if (this.chunks.size === this.total) {
          let assembled = "";
          for (let i = 0; i < this.total; i++) {
            const part = this.chunks.get(i);
            if (part === undefined) {
              return {
                complete: false,
                receivedCount: this.chunks.size,
                totalCount: this.total,
              };
            }
            assembled += part;
          }
          const actualChecksum = computeShortChecksum(assembled);
          if (actualChecksum !== this.checksum) {
            this.reset();
            throw new Error("Multi-frame QR checksum mismatch; restarting scan");
          }
          return {
            complete: true,
            fullPayload: assembled,
            receivedCount: this.total,
            totalCount: this.total,
          };
        }

        return {
          complete: false,
          receivedCount: this.chunks.size,
          totalCount: this.total,
        };
      }
    } catch (err) {
      if (err instanceof Error && err.message.includes("checksum mismatch")) {
        throw err;
      }
    }

    // Single-frame QR payload
    return {
      complete: true,
      fullPayload: trimmed,
      receivedCount: 1,
      totalCount: 1,
    };
  }
}

/**
 * Helper to generate or derive a deterministic demo Vault keypair from a seed phrase or random bytes
 */
export function generateDemoVaultKeyPair(seedHex?: string): {
  publicKey: string;
  implicitAccountId: string;
  secretKeyBase58: string;
  seedBytes: Uint8Array;
} {
  let seed: Uint8Array;
  if (seedHex && seedHex.length === 64) {
    seed = new Uint8Array(32);
    for (let i = 0; i < 32; i++) {
      seed[i] = parseInt(seedHex.slice(i * 2, i * 2 + 2), 16);
    }
  } else {
    seed = nacl.randomBytes(32);
  }
  const kp = nacl.sign.keyPair.fromSeed(seed);
  return {
    publicKey: formatNearPublicKey(kp.publicKey),
    implicitAccountId: bytesToHex(kp.publicKey),
    secretKeyBase58: `ed25519:${encodeBase58(kp.secretKey)}`,
    seedBytes: seed,
  };
}

export { decodeBase58, encodeBase58 };

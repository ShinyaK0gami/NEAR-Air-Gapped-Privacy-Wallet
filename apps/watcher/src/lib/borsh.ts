/**
 * NearAir Deterministic Borsh Serialization & Deserialization for NEAR Protocol
 *
 * Implements the exact NEAR Protocol Borsh binary schema for:
 * - PublicKey (Ed25519)
 * - Action (Transfer, FunctionCall, AddKey)
 * - Transaction (Unsigned)
 * - Signature & SignedTransaction
 *
 * Byte-for-byte identical with `near-api-js` and `nearair-vault` (Kotlin).
 */

const BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

export type NearActionType = "Transfer" | "FunctionCall" | "AddKey";

export interface TransferActionParams {
  deposit: string; // yoctoNEAR string (u128)
}

export interface FunctionCallActionParams {
  methodName: string;
  args: Record<string, unknown> | string; // JSON object or UTF-8/base64 string
  argsBase64?: string;
  gas: string; // gas units (u64), e.g., "30000000000000" (30 TGas)
  deposit: string; // yoctoNEAR string (u128)
}

export interface AddKeyActionParams {
  publicKey: string; // ed25519:...
  nonce?: number;
  permission: "FullAccess" | {
    receiverId: string;
    allowance?: string; // yoctoNEAR string (u128)
    methodNames: string[];
  };
}

export type NearActionDescriptor =
  | { type: "Transfer"; params: TransferActionParams }
  | { type: "FunctionCall"; params: FunctionCallActionParams }
  | { type: "AddKey"; params: AddKeyActionParams };

export interface NearTransactionDescriptor {
  signerId: string;
  publicKey: string; // "ed25519:<base58>" or "<base58>"
  nonce: number | bigint;
  receiverId: string;
  blockHash: string; // base58 32-byte block hash
  actions: NearActionDescriptor[];
}

export interface DeserializedSignedTransaction {
  transaction: NearTransactionDescriptor;
  unsignedBorshBytes: Uint8Array;
  keyType: number;
  signatureBytes: Uint8Array;
  signatureBase64: string;
  signatureBase58: string;
}

/**
 * Base58 Encoding (Bitcoin/NEAR alphabet)
 */
export function encodeBase58(bytes: Uint8Array): string {
  if (bytes.length === 0) return "";
  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) {
    zeros++;
  }
  const size = Math.ceil((bytes.length * Math.log(256)) / Math.log(58)) + 1;
  const b58 = new Uint8Array(size);
  let length = 0;

  for (let i = zeros; i < bytes.length; i++) {
    let carry = bytes[i];
    let j = 0;
    for (let k = size - 1; (carry !== 0 || j < length) && k >= 0; k--, j++) {
      carry += 256 * b58[k];
      b58[k] = carry % 58;
      carry = Math.floor(carry / 58);
    }
    length = j;
  }

  let it = size - length;
  while (it < size && b58[it] === 0) {
    it++;
  }

  let result = "1".repeat(zeros);
  for (; it < size; it++) {
    result += BASE58_ALPHABET[b58[it]];
  }
  return result;
}

/**
 * Base58 Decoding
 */
export function decodeBase58(input: string): Uint8Array {
  const cleaned = input.trim();
  if (cleaned.length === 0) return new Uint8Array(0);

  let zeros = 0;
  while (zeros < cleaned.length && cleaned[zeros] === "1") {
    zeros++;
  }

  const size = Math.ceil((cleaned.length * Math.log(58)) / Math.log(256)) + 1;
  const b256 = new Uint8Array(size);
  let length = 0;

  for (let i = zeros; i < cleaned.length; i++) {
    const ch = cleaned[i];
    const carryInit = BASE58_ALPHABET.indexOf(ch);
    if (carryInit === -1) {
      throw new Error(`Invalid Base58 character "${ch}" in "${input}"`);
    }
    let carry = carryInit;
    let j = 0;
    for (let k = size - 1; (carry !== 0 || j < length) && k >= 0; k--, j++) {
      carry += 58 * b256[k];
      b256[k] = carry % 256;
      carry = Math.floor(carry / 256);
    }
    length = j;
  }

  let it = size - length;
  while (it < size && b256[it] === 0) {
    it++;
  }

  const out = new Uint8Array(zeros + (size - it));
  out.fill(0, 0, zeros);
  let idx = zeros;
  for (; it < size; it++) {
    out[idx++] = b256[it];
  }
  return out;
}

/**
 * Base64 helpers (browser + Node safe)
 */
export function bytesToBase64(bytes: Uint8Array): string {
  if (typeof Buffer !== "undefined") {
    return Buffer.from(bytes).toString("base64");
  }
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export function base64ToBytes(base64: string): Uint8Array {
  const cleaned = base64.trim();
  if (typeof Buffer !== "undefined") {
    return new Uint8Array(Buffer.from(cleaned, "base64"));
  }
  const binary = atob(cleaned);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function hexToBytes(hex: string): Uint8Array {
  const cleaned = hex.trim().replace(/^0x/i, "");
  if (cleaned.length % 2 !== 0) {
    throw new Error("Hex string must have an even number of characters");
  }
  const out = new Uint8Array(cleaned.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(cleaned.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/**
 * Convert human-readable NEAR amount (e.g. "1.5") to yoctoNEAR string (10^24)
 */
export function nearToYocto(nearAmount: string | number): string {
  const raw = String(nearAmount).trim();
  if (!/^\d+(\.\d+)?$/.test(raw)) {
    throw new Error(`Invalid NEAR amount: "${raw}"`);
  }
  const [wholePart, fracPart = ""] = raw.split(".");
  if (fracPart.length > 24) {
    throw new Error("NEAR amount cannot exceed 24 decimal places");
  }
  const paddedFrac = fracPart.padEnd(24, "0");
  const combined = (wholePart + paddedFrac).replace(/^0+/, "") || "0";
  return BigInt(combined).toString(10);
}

/**
 * Convert yoctoNEAR string to human-readable NEAR string
 */
export function yoctoToNear(yoctoAmount: string | bigint, maxDecimals = 6): string {
  const val = BigInt(yoctoAmount).toString(10);
  const padded = val.padStart(25, "0");
  const whole = padded.slice(0, padded.length - 24).replace(/^0+/, "") || "0";
  const frac = padded.slice(padded.length - 24);
  const trimmedFrac = frac.slice(0, maxDecimals).replace(/0+$/, "");
  return trimmedFrac.length > 0 ? `${whole}.${trimmedFrac}` : whole;
}

/**
 * Parse NEAR public key string ("ed25519:..." or raw base58) into 32 raw bytes
 */
export function parseNearPublicKey(publicKeyStr: string): { keyType: number; data: Uint8Array } {
  const trimmed = publicKeyStr.trim();
  const base58Part = trimmed.startsWith("ed25519:")
    ? trimmed.slice("ed25519:".length)
    : trimmed;
  const decoded = decodeBase58(base58Part);
  if (decoded.length !== 32) {
    throw new Error(`Invalid Ed25519 public key length: expected 32 bytes, got ${decoded.length}`);
  }
  return { keyType: 0, data: decoded };
}

export function formatNearPublicKey(bytes: Uint8Array): string {
  if (bytes.length !== 32) {
    throw new Error(`Expected 32-byte Ed25519 public key, got ${bytes.length}`);
  }
  return `ed25519:${encodeBase58(bytes)}`;
}

/**
 * Binary Borsh Writer
 */
class BorshWriter {
  private chunks: Uint8Array[] = [];

  writeU8(value: number): void {
    this.chunks.push(new Uint8Array([value & 0xff]));
  }

  writeU32(value: number): void {
    const buf = new Uint8Array(4);
    const view = new DataView(buf.buffer);
    view.setUint32(0, value >>> 0, true);
    this.chunks.push(buf);
  }

  writeU64(value: bigint | number): void {
    let big = BigInt(value);
    const buf = new Uint8Array(8);
    for (let i = 0; i < 8; i++) {
      buf[i] = Number(big & 0xffn);
      big >>= 8n;
    }
    this.chunks.push(buf);
  }

  writeU128(value: bigint | string): void {
    let big = BigInt(value);
    const buf = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
      buf[i] = Number(big & 0xffn);
      big >>= 8n;
    }
    this.chunks.push(buf);
  }

  writeFixedArray(bytes: Uint8Array): void {
    this.chunks.push(new Uint8Array(bytes));
  }

  writeVecU8(bytes: Uint8Array): void {
    this.writeU32(bytes.length);
    this.writeFixedArray(bytes);
  }

  writeString(str: string): void {
    const encoded = new TextEncoder().encode(str);
    this.writeVecU8(encoded);
  }

  writePublicKey(pubKeyStr: string): void {
    const { keyType, data } = parseNearPublicKey(pubKeyStr);
    this.writeU8(keyType);
    this.writeFixedArray(data);
  }

  toBytes(): Uint8Array {
    const totalLength = this.chunks.reduce((acc, c) => acc + c.length, 0);
    const result = new Uint8Array(totalLength);
    let offset = 0;
    for (const chunk of this.chunks) {
      result.set(chunk, offset);
      offset += chunk.length;
    }
    return result;
  }
}

/**
 * Binary Borsh Reader
 */
class BorshReader {
  private offset = 0;
  private view: DataView;
  private readonly bytes: Uint8Array;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  private ensureAvailable(len: number): void {
    if (this.offset + len > this.bytes.length) {
      throw new Error(
        `Borsh buffer underflow at offset ${this.offset}: needed ${len} bytes, remaining ${this.bytes.length - this.offset}`
      );
    }
  }

  readU8(): number {
    this.ensureAvailable(1);
    return this.bytes[this.offset++];
  }

  readU32(): number {
    this.ensureAvailable(4);
    const val = this.view.getUint32(this.offset, true);
    this.offset += 4;
    return val;
  }

  readU64(): bigint {
    this.ensureAvailable(8);
    let result = 0n;
    for (let i = 7; i >= 0; i--) {
      result = (result << 8n) | BigInt(this.bytes[this.offset + i]);
    }
    this.offset += 8;
    return result;
  }

  readU128(): bigint {
    this.ensureAvailable(16);
    let result = 0n;
    for (let i = 15; i >= 0; i--) {
      result = (result << 8n) | BigInt(this.bytes[this.offset + i]);
    }
    this.offset += 16;
    return result;
  }

  readFixedArray(length: number): Uint8Array {
    this.ensureAvailable(length);
    const slice = this.bytes.slice(this.offset, this.offset + length);
    this.offset += length;
    return slice;
  }

  readVecU8(): Uint8Array {
    const len = this.readU32();
    return this.readFixedArray(len);
  }

  readString(): string {
    const utf8 = this.readVecU8();
    return new TextDecoder().decode(utf8);
  }

  readPublicKey(): string {
    const keyType = this.readU8();
    if (keyType !== 0) {
      throw new Error(`Unsupported NEAR keyType ${keyType}; only Ed25519 (0) is supported`);
    }
    const data = this.readFixedArray(32);
    return formatNearPublicKey(data);
  }

  getOffset(): number {
    return this.offset;
  }

  remaining(): number {
    return this.bytes.length - this.offset;
  }
}

/**
 * Serialize an unsigned NEAR Transaction into raw Borsh bytes
 */
export function serializeNearTransaction(tx: NearTransactionDescriptor): Uint8Array {
  const writer = new BorshWriter();

  // 1. signerId: String
  writer.writeString(tx.signerId.trim());

  // 2. publicKey: PublicKey (u8 keyType + 32 bytes)
  writer.writePublicKey(tx.publicKey);

  // 3. nonce: u64
  writer.writeU64(BigInt(Math.max(1, Math.floor(Number(tx.nonce) || 1))));

  // 4. receiverId: String
  writer.writeString(tx.receiverId.trim());

  // 5. blockHash: [u8; 32]
  const blockHashBytes = decodeBase58(tx.blockHash.trim());
  if (blockHashBytes.length !== 32) {
    throw new Error(`Invalid blockHash length: expected 32 bytes, got ${blockHashBytes.length}`);
  }
  writer.writeFixedArray(blockHashBytes);

  // 6. actions: Vec<Action>
  writer.writeU32(tx.actions.length);
  for (const action of tx.actions) {
    switch (action.type) {
      case "Transfer": {
        writer.writeU8(3); // Action::Transfer discriminant = 3
        writer.writeU128(BigInt(action.params.deposit));
        break;
      }
      case "FunctionCall": {
        writer.writeU8(2); // Action::FunctionCall discriminant = 2
        writer.writeString(action.params.methodName);
        let argsBytes: Uint8Array;
        if (action.params.argsBase64) {
          argsBytes = base64ToBytes(action.params.argsBase64);
        } else if (typeof action.params.args === "string") {
          argsBytes = new TextEncoder().encode(action.params.args);
        } else {
          argsBytes = new TextEncoder().encode(JSON.stringify(action.params.args));
        }
        writer.writeVecU8(argsBytes);
        writer.writeU64(BigInt(action.params.gas));
        writer.writeU128(BigInt(action.params.deposit));
        break;
      }
      case "AddKey": {
        writer.writeU8(5); // Action::AddKey discriminant = 5
        writer.writePublicKey(action.params.publicKey);
        writer.writeU64(BigInt(action.params.nonce ?? 0));
        if (action.params.permission === "FullAccess") {
          writer.writeU8(1); // AccessKeyPermission::FullAccess = 1
        } else {
          writer.writeU8(0); // AccessKeyPermission::FunctionCall = 0
          if (action.params.permission.allowance) {
            writer.writeU8(1); // Option::Some
            writer.writeU128(BigInt(action.params.permission.allowance));
          } else {
            writer.writeU8(0); // Option::None
          }
          writer.writeString(action.params.permission.receiverId);
          writer.writeU32(action.params.permission.methodNames.length);
          for (const method of action.params.permission.methodNames) {
            writer.writeString(method);
          }
        }
        break;
      }
      default:
        throw new Error(`Unsupported action type`);
    }
  }

  return writer.toBytes();
}

/**
 * Deserialize raw Borsh bytes back into a NearTransactionDescriptor
 */
export function deserializeNearTransaction(bytes: Uint8Array): NearTransactionDescriptor {
  const reader = new BorshReader(bytes);
  const { tx } = readTransactionFromReader(reader);
  if (reader.remaining() !== 0) {
    throw new Error(`Unexpected trailing bytes (${reader.remaining()}) in unsigned Borsh transaction`);
  }
  return tx;
}

function readTransactionFromReader(reader: BorshReader): {
  tx: NearTransactionDescriptor;
  byteLength: number;
} {
  const startOffset = reader.getOffset();
  const signerId = reader.readString();
  const publicKey = reader.readPublicKey();
  const nonce = Number(reader.readU64());
  const receiverId = reader.readString();
  const blockHashBytes = reader.readFixedArray(32);
  const blockHash = encodeBase58(blockHashBytes);

  const actionCount = reader.readU32();
  const actions: NearActionDescriptor[] = [];

  for (let i = 0; i < actionCount; i++) {
    const actionType = reader.readU8();
    switch (actionType) {
      case 3: {
        // Transfer
        const deposit = reader.readU128().toString(10);
        actions.push({
          type: "Transfer",
          params: { deposit },
        });
        break;
      }
      case 2: {
        // FunctionCall
        const methodName = reader.readString();
        const argsBytes = reader.readVecU8();
        const gas = reader.readU64().toString(10);
        const deposit = reader.readU128().toString(10);
        let parsedArgs: Record<string, unknown> | string;
        try {
          const decodedText = new TextDecoder("utf-8", { fatal: true }).decode(argsBytes);
          try {
            parsedArgs = JSON.parse(decodedText);
          } catch {
            parsedArgs = decodedText;
          }
        } catch {
          parsedArgs = bytesToBase64(argsBytes);
        }
        actions.push({
          type: "FunctionCall",
          params: {
            methodName,
            args: parsedArgs,
            argsBase64: bytesToBase64(argsBytes),
            gas,
            deposit,
          },
        });
        break;
      }
      case 5: {
        // AddKey
        const addedPubKey = reader.readPublicKey();
        const keyNonce = Number(reader.readU64());
        const permType = reader.readU8();
        if (permType === 1) {
          actions.push({
            type: "AddKey",
            params: {
              publicKey: addedPubKey,
              nonce: keyNonce,
              permission: "FullAccess",
            },
          });
        } else if (permType === 0) {
          const hasAllowance = reader.readU8();
          const allowance = hasAllowance === 1 ? reader.readU128().toString(10) : undefined;
          const permReceiverId = reader.readString();
          const methodsLen = reader.readU32();
          const methodNames: string[] = [];
          for (let m = 0; m < methodsLen; m++) {
            methodNames.push(reader.readString());
          }
          actions.push({
            type: "AddKey",
            params: {
              publicKey: addedPubKey,
              nonce: keyNonce,
              permission: {
                receiverId: permReceiverId,
                allowance,
                methodNames,
              },
            },
          });
        } else {
          throw new Error(`Invalid AddKey permission discriminant: ${permType}`);
        }
        break;
      }
      default:
        throw new Error(`Unsupported NEAR Action discriminant in Borsh payload: ${actionType}`);
    }
  }

  return {
    tx: {
      signerId,
      publicKey,
      nonce,
      receiverId,
      blockHash,
      actions,
    },
    byteLength: reader.getOffset() - startOffset,
  };
}

/**
 * Construct SignedTransaction Borsh bytes from unsigned Borsh bytes and 64-byte Ed25519 signature
 */
export function serializeSignedNearTransaction(
  unsignedBorshBytes: Uint8Array,
  signatureBytes: Uint8Array
): Uint8Array {
  if (signatureBytes.length !== 64) {
    throw new Error(`Expected 64-byte Ed25519 signature, got ${signatureBytes.length}`);
  }
  const signed = new Uint8Array(unsignedBorshBytes.length + 1 + 64);
  signed.set(unsignedBorshBytes, 0);
  signed[unsignedBorshBytes.length] = 0; // keyType = 0 (ED25519)
  signed.set(signatureBytes, unsignedBorshBytes.length + 1);
  return signed;
}

/**
 * Deserialize SignedTransaction Borsh bytes into unsigned Transaction + Ed25519 signature
 */
export function deserializeSignedNearTransaction(
  signedBorshBytes: Uint8Array
): DeserializedSignedTransaction {
  const reader = new BorshReader(signedBorshBytes);
  const { tx, byteLength } = readTransactionFromReader(reader);
  const unsignedBorshBytes = signedBorshBytes.slice(0, byteLength);
  const keyType = reader.readU8();
  if (keyType !== 0) {
    throw new Error(`Unsupported signature keyType: ${keyType}`);
  }
  const signatureBytes = reader.readFixedArray(64);
  if (reader.remaining() !== 0) {
    throw new Error(`Unexpected trailing bytes (${reader.remaining()}) in SignedTransaction`);
  }
  return {
    transaction: tx,
    unsignedBorshBytes,
    keyType,
    signatureBytes,
    signatureBase64: bytesToBase64(signatureBytes),
    signatureBase58: `ed25519:${encodeBase58(signatureBytes)}`,
  };
}

/**
 * Derive 64-character hexadecimal Implicit Account ID from an Ed25519 public key.
 * Direct transfers to this address auto-create the account on NEAR Protocol.
 */
export function deriveImplicitAccountId(publicKey: string): string {
  try {
    const clean = publicKey.replace(/^ed25519:/, "").trim();
    const bytes = decodeBase58(clean);
    if (bytes.length === 32) {
      return Array.from(bytes)
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
    }
  } catch {}
  return "";
}

/**
 * Compute SHA-256 digest (32 bytes) in browser or Node
 */
export async function sha256Bytes(data: Uint8Array): Promise<Uint8Array> {
  if (typeof globalThis.crypto !== "undefined" && globalThis.crypto.subtle) {
    const copy = new Uint8Array(data);
    const digest = await globalThis.crypto.subtle.digest("SHA-256", copy.buffer);
    return new Uint8Array(digest);
  }
  const nodeCrypto = await import("crypto");
  return new Uint8Array(nodeCrypto.createHash("sha256").update(data).digest());
}

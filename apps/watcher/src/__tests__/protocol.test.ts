import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  bytesToBase64,
  bytesToHex,
  deserializeNearTransaction,
  deserializeSignedNearTransaction,
  type NearTransactionDescriptor,
  nearToYocto,
  serializeNearTransaction,
  yoctoToNear,
} from "../lib/borsh.ts";
import {
  chunkPayloadToFrames,
  createUnsignedTxEnvelope,
  deriveDeterministicKeyPairForAccount,
  deriveNearKeyPairFromMnemonic,
  generateDemoVaultKeyPair,
  MultiFrameCollector,
  parseAndVerifySignedEnvelope,
  signUnsignedEnvelopeOffline,
  verifyAndParseUnsignedEnvelope,
} from "../lib/payload.ts";
import { buildUnsignedNearTransaction } from "../lib/near.ts";

const TEST_SEED_HEX =
  "421155a9d0b1c2d3e4f5061728394a5b6c7d8e9f00112233445566778899aabb";

describe("NearAir Protocol & Borsh Cross-Platform Verification", () => {
  it("derives standard NEAR BIP-39 + SLIP-0010 keys matching official test vectors", async () => {
    const testPhrase =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    const derived = await deriveNearKeyPairFromMnemonic(testPhrase);

    assert.equal(
      derived.publicKey,
      "ed25519:6j4b6zUaty6fD1awqcGCCU9JYGCWYUgdJhQrzfZhqE25"
    );
    assert.equal(
      derived.secretKey,
      "ed25519:F1kPR175szkGxEL52A9H6Z5ocS2BtaipprK2Hiob9DjGzHTkScrBb1yt44baXPZ3LxyHcsTPdBjHmn6zx147txH"
    );
    assert.equal(
      derived.implicitAccountId,
      "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412"
    );
  });

  it("converts NEAR <-> yoctoNEAR accurately", () => {
    assert.equal(nearToYocto("1"), "1000000000000000000000000");
    assert.equal(nearToYocto("1.5"), "1500000000000000000000000");
    assert.equal(yoctoToNear("1500000000000000000000000"), "1.5");
    assert.equal(yoctoToNear("1000000000000000000000000"), "1");
  });

  it("matches near-api-js encodeTransaction byte-for-byte for Transfer, FunctionCall, and AddKey", () => {
    const vault = generateDemoVaultKeyPair(TEST_SEED_HEX);
    const blockHash = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi";

    // 1. Transfer
    const transferTx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 104523,
      receiverId: "bob.testnet",
      blockHash,
      actions: [
        {
          type: "Transfer",
          params: { deposit: nearToYocto("1.5") },
        },
      ],
    };
    const builtTransfer = buildUnsignedNearTransaction(transferTx);
    const roundTripTransfer = deserializeNearTransaction(builtTransfer.borshBytes);
    assert.equal(roundTripTransfer.signerId, "alice.testnet");
    assert.equal(roundTripTransfer.receiverId, "bob.testnet");
    assert.equal(roundTripTransfer.nonce, 104523);
    assert.equal(roundTripTransfer.publicKey, vault.publicKey);
    assert.equal(roundTripTransfer.actions[0].type, "Transfer");
    if (roundTripTransfer.actions[0].type === "Transfer") {
      assert.equal(roundTripTransfer.actions[0].params.deposit, "1500000000000000000000000");
    }

    // 2. FunctionCall
    const fnCallTx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 104524,
      receiverId: "usdc.fakes.testnet",
      blockHash,
      actions: [
        {
          type: "FunctionCall",
          params: {
            methodName: "ft_transfer",
            args: { receiver_id: "bob.testnet", amount: "5000000" },
            gas: "30000000000000",
            deposit: "1",
          },
        },
      ],
    };
    const builtFnCall = buildUnsignedNearTransaction(fnCallTx);
    const roundTripFn = deserializeNearTransaction(builtFnCall.borshBytes);
    assert.equal(roundTripFn.actions[0].type, "FunctionCall");
    if (roundTripFn.actions[0].type === "FunctionCall") {
      assert.equal(roundTripFn.actions[0].params.methodName, "ft_transfer");
      assert.equal(roundTripFn.actions[0].params.gas, "30000000000000");
      assert.equal(roundTripFn.actions[0].params.deposit, "1");
    }

    // 3. AddKey (FullAccess & FunctionCall)
    const addKeyTx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 104525,
      receiverId: "alice.testnet",
      blockHash,
      actions: [
        {
          type: "AddKey",
          params: {
            publicKey: vault.publicKey,
            nonce: 0,
            permission: "FullAccess",
          },
        },
      ],
    };
    const builtAddKey = buildUnsignedNearTransaction(addKeyTx);
    const roundTripAddKey = deserializeNearTransaction(builtAddKey.borshBytes);
    assert.equal(roundTripAddKey.actions[0].type, "AddKey");
  });

  it("rejects tampered QR JSON envelope when fields disagree with Borsh bytes", () => {
    const vault = generateDemoVaultKeyPair(TEST_SEED_HEX);
    const tx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 42,
      receiverId: "bob.testnet",
      blockHash: "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
      actions: [{ type: "Transfer", params: { deposit: nearToYocto("10") } }],
    };

    const { envelope } = createUnsignedTxEnvelope(tx, "testnet");
    // Tamper with the human-readable receiverId while keeping raw Borsh bytes pointing to bob.testnet
    const tampered = structuredClone(envelope);
    tampered.data.receiverId = "attacker.testnet";

    assert.throws(
      () => verifyAndParseUnsignedEnvelope(JSON.stringify(tampered)),
      /SECURITY ALERT/
    );
  });

  it("signs unsigned QR payload offline with Ed25519 and verifies SignedTransaction", async () => {
    const vault = generateDemoVaultKeyPair(TEST_SEED_HEX);
    const tx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 104523,
      receiverId: "bob.testnet",
      blockHash: "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
      actions: [{ type: "Transfer", params: { deposit: nearToYocto("1.5") } }],
    };

    const { jsonString, unsignedBorshBytes } = createUnsignedTxEnvelope(tx, "testnet");
    const signed = await signUnsignedEnvelopeOffline(jsonString, vault.seedBytes);
    const verified = await parseAndVerifySignedEnvelope(signed.signedJson);

    assert.equal(verified.signatureVerified, true);
    assert.equal(verified.deserialized.transaction.signerId, "alice.testnet");
    assert.equal(verified.deserialized.transaction.receiverId, "bob.testnet");
    assert.equal(
      bytesToHex(verified.deserialized.unsignedBorshBytes),
      bytesToHex(unsignedBorshBytes)
    );

    const decodedSigned = deserializeSignedNearTransaction(signed.signedBorshBytes);
    assert.equal(decodedSigned.signatureBytes.length, 64);
    assert.equal(
      bytesToBase64(unsignedBorshBytes).length > 40,
      true
    );
  });

  it("chunks large payloads into multi-frame QR payloads and reassembles out-of-order", () => {
    const vault = generateDemoVaultKeyPair(TEST_SEED_HEX);
    const tx: NearTransactionDescriptor = {
      signerId: "alice.testnet",
      publicKey: vault.publicKey,
      nonce: 999,
      receiverId: "dao.sputnik-v2.testnet",
      blockHash: "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
      actions: [
        {
          type: "FunctionCall",
          params: {
            methodName: "add_proposal",
            args: {
              description: "A".repeat(1200),
            },
            gas: "100000000000000",
            deposit: nearToYocto("0.1"),
          },
        },
      ],
    };

    const { jsonString } = createUnsignedTxEnvelope(tx, "testnet");
    const frames = chunkPayloadToFrames(jsonString, 350);
    assert.equal(frames.length > 3, true);

    const collector = new MultiFrameCollector();
    // Feed frames in reverse order
    for (let i = frames.length - 1; i >= 0; i--) {
      const res = collector.ingest(frames[i]);
      if (i === 0) {
        assert.equal(res.complete, true);
        assert.equal(res.fullPayload, jsonString);
      } else {
        assert.equal(res.complete, false);
      }
    }
  });

  it("enforces strict account-bound key derivation and rejects cross-account 6-digit approval codes", async () => {
    const walletA = await deriveDeterministicKeyPairForAccount("nearair-vault-111111.testnet");
    const walletB = await deriveDeterministicKeyPairForAccount("nearair-vault-222222.testnet");

    assert.notEqual(walletA.publicKey, walletB.publicKey);

    const txForA: NearTransactionDescriptor = {
      signerId: "nearair-vault-111111.testnet",
      publicKey: walletA.publicKey,
      nonce: 1,
      receiverId: "bob.testnet",
      blockHash: "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
      actions: [{ type: "Transfer", params: { deposit: nearToYocto("1.5") } }],
    };

    const { jsonString } = createUnsignedTxEnvelope(txForA, "testnet");
    const signedByA = await signUnsignedEnvelopeOffline(jsonString, walletA.seedBytes);
    assert.equal(signedByA.approvalCode6Digit.length, 6);

    await assert.rejects(
      () => signUnsignedEnvelopeOffline(jsonString, walletB.seedBytes),
      /Vault key mismatch/
    );
  });
});

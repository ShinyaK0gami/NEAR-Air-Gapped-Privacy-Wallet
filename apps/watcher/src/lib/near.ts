/**
 * NearAir Online Watcher RPC & Transaction Construction Service
 *
 * Uses `near-api-js` and JSON-RPC to query NEAR Testnet/Mainnet state,
 * construct unsigned transactions (`nearApi.transactions.createTransaction`),
 * serialize to Borsh, and broadcast signed transactions (`broadcast_tx_commit`).
 */

import * as nearApi from "near-api-js";
import {
  base64ToBytes,
  bytesToBase64,
  decodeBase58,
  type NearActionDescriptor,
  type NearTransactionDescriptor,
  serializeNearTransaction,
  yoctoToNear,
} from "./borsh.ts";

export type NearNetworkId = "testnet" | "mainnet";

export const NEAR_RPC_ENDPOINTS: Record<NearNetworkId, string[]> = {
  testnet: [
    typeof window !== "undefined" ? "/api/rpc?network=testnet" : "https://rpc.testnet.near.org",
    "https://rpc.testnet.near.org",
    "https://test.rpc.fastnear.com",
    "https://archival-rpc.testnet.near.org",
  ],
  mainnet: [
    typeof window !== "undefined" ? "/api/rpc?network=mainnet" : "https://rpc.mainnet.near.org",
    "https://free.rpc.fastnear.com",
    "https://rpc.mainnet.near.org",
  ],
};

export const NEAR_EXPLORER_URLS: Record<NearNetworkId, string> = {
  testnet: "https://testnet.nearblocks.io",
  mainnet: "https://nearblocks.io",
};

export interface NearAccessKeyInfo {
  publicKey: string;
  nonce: number;
  permission: "FullAccess" | {
    FunctionCall: {
      allowance: string | null;
      receiver_id: string;
      method_names: string[];
    };
  };
}

export interface WatchedAccountState {
  accountId: string;
  networkId: NearNetworkId;
  exists: boolean;
  amountYocto: string;
  amountNear: string;
  lockedYocto: string;
  lockedNear: string;
  storageUsage: number;
  blockHash: string;
  blockHeight: number;
  accessKeys: NearAccessKeyInfo[];
  matchedKeyNonce: number | null;
  nextNonce: number;
  rpcEndpointUsed: string;
}

export interface BroadcastResult {
  txHash: string;
  explorerUrl: string;
  status: unknown;
  rawOutcome: unknown;
}

/**
 * Perform a JSON-RPC call with automatic endpoint fallback
 */
async function rpcRequest<T>(
  networkId: NearNetworkId,
  method: string,
  params: Record<string, unknown> | unknown[],
  preferredRpcUrl?: string
): Promise<{ result: T; endpoint: string }> {
  const endpoints = preferredRpcUrl
    ? [
        preferredRpcUrl,
        ...NEAR_RPC_ENDPOINTS[networkId].filter((e) => e !== preferredRpcUrl),
      ]
    : NEAR_RPC_ENDPOINTS[networkId];

  let lastError: Error | null = null;

  for (const endpoint of endpoints) {
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: "nearair-watcher",
          method,
          params,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const json = await response.json();
      if (json.error) {
        const errMsg =
          json.error?.cause?.name ||
          json.error?.data ||
          json.error?.message ||
          JSON.stringify(json.error);
        const err = new Error(String(errMsg));
        (err as Error & { rpcCause?: string }).rpcCause = json.error?.cause?.name;
        throw err;
      }

      return { result: json.result as T, endpoint };
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      // If it's an authoritative RPC state error (like UNKNOWN_ACCOUNT or UNKNOWN_ACCESS_KEY), don't retry other endpoints
      const cause = (lastError as Error & { rpcCause?: string }).rpcCause;
      if (cause === "UNKNOWN_ACCOUNT" || cause === "UNKNOWN_ACCESS_KEY") {
        throw lastError;
      }
    }
  }

  throw lastError || new Error(`Failed to reach NEAR ${networkId} RPC`);
}

/**
 * Fetch latest finalized block hash and height from NEAR RPC
 */
export async function fetchLatestBlock(
  networkId: NearNetworkId = "testnet",
  preferredRpcUrl?: string
): Promise<{ blockHash: string; blockHeight: number; endpoint: string }> {
  const { result, endpoint } = await rpcRequest<{
    header: { hash: string; height: number };
  }>(
    networkId,
    "block",
    { finality: "final" },
    preferredRpcUrl
  );

  return {
    blockHash: result.header.hash,
    blockHeight: result.header.height,
    endpoint,
  };
}

/**
 * Query full account state, balances, access keys, and nonce for the watched account
 */
export async function fetchWatchedAccountState(
  accountId: string,
  vaultPublicKey?: string,
  networkId: NearNetworkId = "testnet",
  preferredRpcUrl?: string
): Promise<WatchedAccountState> {
  const cleanedAccount = accountId.trim();
  if (!cleanedAccount) {
    throw new Error("Account ID is required");
  }

  // 1. Always get the latest finalized block hash first
  const latestBlock = await fetchLatestBlock(networkId, preferredRpcUrl);

  // 2. Query account state (`view_account`)
  let amountYocto = "0";
  let lockedYocto = "0";
  let storageUsage = 0;
  let blockHash = latestBlock.blockHash;
  let blockHeight = latestBlock.blockHeight;
  let exists = true;

  try {
    const { result: accountView } = await rpcRequest<{
      amount: string;
      locked: string;
      storage_usage: number;
      block_hash: string;
      block_height: number;
    }>(
      networkId,
      "query",
      {
        request_type: "view_account",
        finality: "final",
        account_id: cleanedAccount,
      },
      latestBlock.endpoint
    );

    amountYocto = accountView.amount;
    lockedYocto = accountView.locked;
    storageUsage = accountView.storage_usage;
    blockHash = accountView.block_hash || latestBlock.blockHash;
    blockHeight = accountView.block_height || latestBlock.blockHeight;
  } catch (err) {
    const cause = (err as Error & { rpcCause?: string }).rpcCause;
    if (cause === "UNKNOWN_ACCOUNT" || String(err).includes("UNKNOWN_ACCOUNT") || String(err).includes("does not exist")) {
      exists = false;
    } else {
      throw err;
    }
  }

  // 3. Query access key list (`view_access_key_list`) if account exists
  const accessKeys: NearAccessKeyInfo[] = [];
  if (exists) {
    try {
      const { result: keyList } = await rpcRequest<{
        keys: Array<{
          public_key: string;
          access_key: {
            nonce: number;
            permission: NearAccessKeyInfo["permission"];
          };
        }>;
      }>(
        networkId,
        "query",
        {
          request_type: "view_access_key_list",
          finality: "final",
          account_id: cleanedAccount,
        },
        latestBlock.endpoint
      );

      if (Array.isArray(keyList.keys)) {
        for (const k of keyList.keys) {
          accessKeys.push({
            publicKey: k.public_key,
            nonce: Number(k.access_key.nonce),
            permission: k.access_key.permission,
          });
        }
      }
    } catch {
      // Ignore key list error if account has no keys
    }
  }

  // 4. Match the Vault's public key if provided, or pick the first FullAccess key
  let matchedKeyNonce: number | null = null;
  if (vaultPublicKey && vaultPublicKey.trim()) {
    const normalizedVaultKey = vaultPublicKey.trim().startsWith("ed25519:")
      ? vaultPublicKey.trim()
      : `ed25519:${vaultPublicKey.trim()}`;
    const found = accessKeys.find((k) => k.publicKey === normalizedVaultKey);
    if (found) {
      matchedKeyNonce = found.nonce;
    } else if (exists) {
      // Also try querying view_access_key directly
      try {
        const { result: directKey } = await rpcRequest<{
          nonce?: number;
          block_hash?: string;
          error?: string;
        }>(
          networkId,
          "query",
          {
            request_type: "view_access_key",
            finality: "final",
            account_id: cleanedAccount,
            public_key: normalizedVaultKey,
          },
          latestBlock.endpoint
        );
        if (
          !directKey.error &&
          typeof directKey.nonce === "number" &&
          Number.isFinite(directKey.nonce)
        ) {
          matchedKeyNonce = Number(directKey.nonce);
          if (directKey.block_hash) {
            blockHash = directKey.block_hash;
          }
        }
      } catch {
        matchedKeyNonce = null;
      }
    }
  }

  // Fallback to the first FullAccess key's nonce on the account so nextNonce is never NaN
  if (matchedKeyNonce === null || !Number.isFinite(matchedKeyNonce)) {
    const firstFullAccess =
      accessKeys.find((k) => k.permission === "FullAccess") || accessKeys[0];
    if (firstFullAccess && Number.isFinite(firstFullAccess.nonce)) {
      matchedKeyNonce = firstFullAccess.nonce;
    } else {
      matchedKeyNonce = null;
    }
  }

  const nextNonce =
    matchedKeyNonce !== null && Number.isFinite(matchedKeyNonce)
      ? matchedKeyNonce + 1
      : 1;

  return {
    accountId: cleanedAccount,
    networkId,
    exists,
    amountYocto,
    amountNear: yoctoToNear(amountYocto, 5),
    lockedYocto,
    lockedNear: yoctoToNear(lockedYocto, 5),
    storageUsage,
    blockHash,
    blockHeight,
    accessKeys,
    matchedKeyNonce,
    nextNonce,
    rpcEndpointUsed: latestBlock.endpoint,
  };
}

/**
 * Construct an unsigned NEAR transaction using `near-api-js` and return both the
 * `near-api-js` Transaction object and its Borsh-serialized bytes.
 */
export function buildUnsignedNearTransaction(
  txDesc: NearTransactionDescriptor
): {
  nearApiTx: nearApi.transactions.Transaction;
  borshBytes: Uint8Array;
} {
  const pubKeyStr = txDesc.publicKey.startsWith("ed25519:")
    ? txDesc.publicKey
    : `ed25519:${txDesc.publicKey}`;
  const pubKey = nearApi.utils.PublicKey.fromString(pubKeyStr);
  const blockHashBytes = decodeBase58(txDesc.blockHash);

  const nearActions: nearApi.transactions.Action[] = txDesc.actions.map((act) => {
    switch (act.type) {
      case "Transfer":
        return nearApi.transactions.transfer(BigInt(act.params.deposit));
      case "FunctionCall": {
        let argsBytes: Uint8Array;
        if (act.params.argsBase64) {
          argsBytes = base64ToBytes(act.params.argsBase64);
        } else if (typeof act.params.args === "string") {
          argsBytes = new TextEncoder().encode(act.params.args);
        } else {
          argsBytes = new TextEncoder().encode(JSON.stringify(act.params.args));
        }
        return nearApi.transactions.functionCall(
          act.params.methodName,
          argsBytes,
          BigInt(act.params.gas),
          BigInt(act.params.deposit)
        );
      }
      case "AddKey": {
        const addedKey = nearApi.utils.PublicKey.fromString(
          act.params.publicKey.startsWith("ed25519:")
            ? act.params.publicKey
            : `ed25519:${act.params.publicKey}`
        );
        const accessKey =
          act.params.permission === "FullAccess"
            ? nearApi.transactions.fullAccessKey()
            : nearApi.transactions.functionCallAccessKey(
                act.params.permission.receiverId,
                act.params.permission.methodNames,
                act.params.permission.allowance
                  ? BigInt(act.params.permission.allowance)
                  : undefined
              );
        return nearApi.transactions.addKey(addedKey, accessKey);
      }
    }
  });

  const safeNonce = BigInt(Math.max(1, Math.floor(Number(txDesc.nonce) || 1)));
  const nearApiTx = nearApi.transactions.createTransaction(
    txDesc.signerId.trim(),
    pubKey,
    txDesc.receiverId.trim(),
    safeNonce,
    nearActions,
    blockHashBytes
  );

  // Serialize with our deterministic Borsh serializer and verify parity with near-api-js encodeTransaction
  const borshBytes = serializeNearTransaction(txDesc);
  const nearApiEncoded = nearApi.transactions.encodeTransaction(nearApiTx);

  if (bytesToBase64(borshBytes) !== bytesToBase64(nearApiEncoded)) {
    throw new Error(
      "Internal Borsh mismatch between NearAir Borsh serializer and near-api-js encodeTransaction"
    );
  }

  return {
    nearApiTx,
    borshBytes,
  };
}

/**
 * Broadcast signed Borsh transaction bytes (`SignedTransaction`) to NEAR RPC (`broadcast_tx_commit`)
 */
export async function broadcastSignedTransaction(
  signedBorshBytes: Uint8Array,
  networkId: NearNetworkId = "testnet",
  preferredRpcUrl?: string
): Promise<BroadcastResult> {
  const signedBase64 = bytesToBase64(signedBorshBytes);

  const { result } = await rpcRequest<{
    transaction: { hash: string };
    status: unknown;
  }>(
    networkId,
    "broadcast_tx_commit",
    [signedBase64],
    preferredRpcUrl
  );

  const txHash = result.transaction.hash;
  const explorerUrl = `${NEAR_EXPLORER_URLS[networkId]}/txns/${txHash}`;

  return {
    txHash,
    explorerUrl,
    status: result.status,
    rawOutcome: result,
  };
}

/**
 * Create and fund a `.testnet` account on the live NEAR Testnet blockchain
 * with 10 NEAR and register `vaultPublicKey` as an on-chain FullAccess key.
 */
export async function createAndFundTestnetAccount(
  accountId: string,
  vaultPublicKey: string
): Promise<{
  created: boolean;
  accountId: string;
  publicKey: string;
  txHash: string | null;
  explorerUrl: string | null;
}> {
  const cleanAccount = accountId.trim().toLowerCase();
  const cleanKey = vaultPublicKey.trim().startsWith("ed25519:")
    ? vaultPublicKey.trim()
    : `ed25519:${vaultPublicKey.trim()}`;

  // 1. Try Next.js API route first (avoids browser CORS if any)
  if (typeof window !== "undefined") {
    try {
      const apiRes = await fetch("/api/create-testnet-account", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          newAccountId: cleanAccount,
          newAccountPublicKey: cleanKey,
        }),
      });
      if (apiRes.ok) {
        return await apiRes.json();
      }
    } catch {
      // Fallback to direct helper call below
    }
  }

  // 2. Direct call to official NEAR Testnet helper
  const response = await fetch("https://helper.testnet.near.org/account", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      newAccountId: cleanAccount,
      newAccountPublicKey: cleanKey,
    }),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`NEAR Testnet Helper error (${response.status}): ${text}`);
  }

  let parsed: Record<string, unknown> = {};
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = {};
  }

  const txHash =
    (parsed?.transaction as { hash?: string } | undefined)?.hash ||
    (parsed?.transaction_outcome as { id?: string } | undefined)?.id ||
    null;

  return {
    created: true,
    accountId: cleanAccount,
    publicKey: cleanKey,
    txHash,
    explorerUrl: txHash ? `https://testnet.nearblocks.io/txns/${txHash}` : null,
  };
}

/**
 * Reverse account lookup: Find all on-chain account IDs registered to an Ed25519 public key.
 * Queries FastNEAR & KitWallet indexers for instant account discovery.
 */
export async function lookupAccountsByPublicKey(
  publicKey: string,
  networkId: NearNetworkId = "testnet"
): Promise<string[]> {
  const cleanKey = publicKey.trim().startsWith("ed25519:")
    ? publicKey.trim()
    : `ed25519:${publicKey.trim()}`;

  const endpoints =
    networkId === "mainnet"
      ? [
          `https://api.fastnear.com/v1/public_key/${cleanKey}`,
          `https://api.kitwallet.app/publicKey/${cleanKey}/accounts`,
        ]
      : [
          `https://test.api.fastnear.com/v1/public_key/${cleanKey}`,
          `https://testnet-api.kitwallet.app/publicKey/${cleanKey}/accounts`,
        ];

  for (const url of endpoints) {
    try {
      const res = await fetch(url);
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data) && data.length > 0) {
          return data;
        } else if (Array.isArray(data.account_ids) && data.account_ids.length > 0) {
          return data.account_ids;
        }
      }
    } catch {}
  }
  return [];
}


import type { NextApiRequest, NextApiResponse } from "next";

/**
 * Server-side proxy to official NEAR Testnet Helper (`https://helper.testnet.near.org/account`)
 *
 * Creates a real `.testnet` account on the NEAR Testnet blockchain, funds it with 10 NEAR,
 * and registers the air-gapped Vault's `ed25519:...` public key as a FullAccess key on-chain.
 */
export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse
) {
  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method not allowed" });
  }

  const { newAccountId, newAccountPublicKey } = req.body || {};
  if (!newAccountId || !newAccountPublicKey) {
    return res
      .status(400)
      .json({ error: "Missing newAccountId or newAccountPublicKey" });
  }

  try {
    const response = await fetch("https://helper.testnet.near.org/account", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        newAccountId: String(newAccountId).trim().toLowerCase(),
        newAccountPublicKey: String(newAccountPublicKey).trim(),
      }),
    });

    const text = await response.text();
    if (!response.ok) {
      return res.status(response.status).json({
        error: `NEAR Testnet Helper returned ${response.status}: ${text}`,
      });
    }

    let parsed: Record<string, unknown> = {};
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = { raw: text };
    }

    const txHash =
      (parsed?.transaction as { hash?: string } | undefined)?.hash ||
      (parsed?.transaction_outcome as { id?: string } | undefined)?.id ||
      null;

    return res.status(200).json({
      created: true,
      accountId: String(newAccountId).trim().toLowerCase(),
      publicKey: String(newAccountPublicKey).trim(),
      txHash,
      explorerUrl: txHash
        ? `https://testnet.nearblocks.io/txns/${txHash}`
        : null,
      outcome: parsed,
    });
  } catch (err) {
    return res.status(500).json({
      error: err instanceof Error ? err.message : String(err),
    });
  }
}


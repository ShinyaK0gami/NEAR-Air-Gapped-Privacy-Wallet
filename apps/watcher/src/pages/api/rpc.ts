import type { NextApiRequest, NextApiResponse } from "next";

const TESTNET_RPCS = [
  "https://rpc.testnet.near.org",
  "https://test.rpc.fastnear.com",
  "https://archival-rpc.testnet.near.org",
];

const MAINNET_RPCS = [
  "https://free.rpc.fastnear.com",
  "https://rpc.mainnet.near.org",
];

export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse
) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method not allowed" });
  }

  const networkId = req.query.network === "mainnet" ? "mainnet" : "testnet";
  const rpcList = networkId === "mainnet" ? MAINNET_RPCS : TESTNET_RPCS;

  let lastError: Error | null = null;
  let lastResponseData: unknown = null;

  for (const rpcUrl of rpcList) {
    try {
      const response = await fetch(rpcUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: typeof req.body === "string" ? req.body : JSON.stringify(req.body),
      });

      const data = await response.json();
      lastResponseData = data;

      // If the node gave a valid JSON-RPC answer (even an RPC error from the chain), return it
      if (data) {
        return res.status(200).json(data);
      }
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
    }
  }

  if (lastResponseData) {
    return res.status(200).json(lastResponseData);
  }

  return res.status(502).json({
    jsonrpc: "2.0",
    id: req.body?.id || "error",
    error: {
      code: -32603,
      message: `Failed to reach NEAR ${networkId} nodes: ${lastError?.message || "Unknown error"}`,
    },
  });
}


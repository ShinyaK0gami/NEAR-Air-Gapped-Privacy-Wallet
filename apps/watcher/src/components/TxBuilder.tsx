import React, { useState } from "react";
import {
  Send,
  Code2,
  KeyRound,
  QrCode,
  AlertTriangle,
  FileCode,
  Sparkles,
  Copy,
  Check,
  ClipboardPaste,
} from "lucide-react";
import {
  NearActionDescriptor,
  NearActionType,
  NearTransactionDescriptor,
  nearToYocto,
} from "@/lib/borsh";
import { buildUnsignedNearTransaction, NearNetworkId } from "@/lib/near";
import {
  createUnsignedTxEnvelope,
  UnsignedTxEnvelope,
} from "@/lib/payload";

interface TxBuilderProps {
  signerId: string;
  vaultPublicKey: string;
  nonce: number;
  blockHash: string;
  networkId: NearNetworkId;
  onUnsignedTxConstructed: (result: {
    envelope: UnsignedTxEnvelope;
    jsonString: string;
    unsignedBorshBytes: Uint8Array;
  }) => void;
}

export const TxBuilder: React.FC<TxBuilderProps> = ({
  signerId,
  vaultPublicKey,
  nonce,
  blockHash,
  networkId,
  onUnsignedTxConstructed,
}) => {
  const [actionType, setActionType] = useState<NearActionType>("Transfer");
  const [receiverId, setReceiverId] = useState(
    networkId === "testnet" ? "bob.testnet" : "bob.near"
  );
  const [amountNear, setAmountNear] = useState("1.5");
  const [copiedField, setCopiedField] = useState<string | null>(null);

  // FunctionCall parameters
  const [methodName, setMethodName] = useState("ft_transfer");
  const [argsJson, setArgsJson] = useState(
    JSON.stringify(
      { receiver_id: "bob.testnet", amount: "1000000", memo: "NearAir Cold Vault" },
      null,
      2
    )
  );
  const [gasTgas, setGasTgas] = useState("30");

  // AddKey parameters
  const [newPublicKey, setNewPublicKey] = useState(
    "ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp"
  );
  const [addKeyPermType, setAddKeyPermType] = useState<"FullAccess" | "FunctionCall">(
    "FullAccess"
  );
  const [allowedContractId, setAllowedContractId] = useState(
    "guestbook.near-examples.testnet"
  );
  const [allowanceNear, setAllowanceNear] = useState("0.25");
  const [allowedMethods, setAllowedMethods] = useState("add_message");

  // Custom override toggles
  const [customNonce, setCustomNonce] = useState<string>("");
  const [error, setError] = useState<string | null>(null);

  const safePropNonce =
    typeof nonce === "number" && Number.isFinite(nonce) && nonce > 0
      ? Math.floor(nonce)
      : 1;
  const parsedCustom = customNonce.trim() ? Number(customNonce) : NaN;
  const effectiveNonce =
    Number.isFinite(parsedCustom) && parsedCustom > 0
      ? Math.floor(parsedCustom)
      : safePropNonce;

  const handleCopyText = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(id);
    setTimeout(() => setCopiedField(null), 1600);
  };

  const handlePasteReceiver = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) setReceiverId(text.trim());
    } catch {
      // ignore clipboard error
    }
  };

  const handleConstructTransaction = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    try {
      if (!signerId.trim()) {
        throw new Error("Signer Account ID is required");
      }
      if (!vaultPublicKey.trim()) {
        throw new Error("Vault Public Key (ed25519:...) is required");
      }
      if (!receiverId.trim()) {
        throw new Error("Receiver Account ID is required");
      }
      if (!blockHash.trim()) {
        throw new Error("Latest Block Hash is required (click Sync RPC first)");
      }

      let actionDescriptor: NearActionDescriptor;

      if (actionType === "Transfer") {
        const depositYocto = nearToYocto(amountNear);
        actionDescriptor = {
          type: "Transfer",
          params: {
            deposit: depositYocto,
          },
        };
      } else if (actionType === "FunctionCall") {
        let parsedArgs: Record<string, unknown>;
        try {
          parsedArgs = JSON.parse(argsJson);
        } catch {
          throw new Error("FunctionCall arguments must be valid JSON");
        }
        const gasUnits = (
          BigInt(Math.round(Number(gasTgas) * 1000)) * 1_000_000_000n
        ).toString(10);
        const depositYocto = nearToYocto(amountNear || "0");
        actionDescriptor = {
          type: "FunctionCall",
          params: {
            methodName: methodName.trim(),
            args: parsedArgs,
            gas: gasUnits,
            deposit: depositYocto,
          },
        };
      } else {
        // AddKey
        if (addKeyPermType === "FullAccess") {
          actionDescriptor = {
            type: "AddKey",
            params: {
              publicKey: newPublicKey.trim(),
              nonce: 0,
              permission: "FullAccess",
            },
          };
        } else {
          actionDescriptor = {
            type: "AddKey",
            params: {
              publicKey: newPublicKey.trim(),
              nonce: 0,
              permission: {
                receiverId: allowedContractId.trim(),
                allowance: allowanceNear ? nearToYocto(allowanceNear) : undefined,
                methodNames: allowedMethods
                  .split(",")
                  .map((m) => m.trim())
                  .filter(Boolean),
              },
            },
          };
        }
      }

      const txDescriptor: NearTransactionDescriptor = {
        signerId: signerId.trim(),
        publicKey: vaultPublicKey.trim(),
        nonce: Math.max(1, Math.floor(Number(effectiveNonce) || 1)),
        receiverId: receiverId.trim(),
        blockHash: blockHash.trim(),
        actions: [actionDescriptor],
      };

      // Validate with near-api-js createTransaction + Borsh parity check
      buildUnsignedNearTransaction(txDescriptor);

      // Package into optical QR JSON envelope
      const result = createUnsignedTxEnvelope(txDescriptor, networkId);
      onUnsignedTxConstructed(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const handleLoadLargeFunctionCallDemo = () => {
    setActionType("FunctionCall");
    setReceiverId("dao.sputnik-v2.testnet");
    setMethodName("add_proposal");
    setAmountNear("0.1");
    setGasTgas("100");
    setArgsJson(
      JSON.stringify(
        {
          proposal: {
            description:
              "NearAir Air-Gapped Cold Storage Multi-Frame Animated QR Code Demonstration Proposal — Verifying large Borsh payload chunking across optical frames with SHA-256 integrity checks and deterministic Ed25519 offline signing.",
            kind: {
              FunctionCall: {
                receiver_id: "treasury.nearair.testnet",
                actions: [
                  {
                    method_name: "execute_cold_vault_payout",
                    args: "eyJyZWNpcGllbnQiOiJib2IudGVzdG5ldCIsImFtb3VudCI6IjEwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAiLCJhdWRpdF90cmFpbCI6Im5lYXJhaXJfdjFfYWlyZ2FwX3ZlcmlmaWVkIn0=",
                    deposit: "100000000000000000000000",
                    gas: "50000000000000",
                  },
                ],
              },
            },
          },
        },
        null,
        2
      )
    );
  };

  let yoctoPreview = "0";
  try {
    yoctoPreview = nearToYocto(amountNear || "0");
  } catch {
    yoctoPreview = "Invalid";
  }

  return (
    <div className="glassify rounded-[28px] p-6 sm:p-7">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl glass-pill flex items-center justify-center text-sky-300 border border-sky-400/30">
            <FileCode className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-extrabold text-white tracking-tight">
              Build Unsigned Transaction
            </h2>
            <p className="text-xs text-slate-300/80">
              Construct &amp; Borsh-serialize NEAR transactions for offline optical signing
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={handleLoadLargeFunctionCallDemo}
          className="text-xs px-3 py-1.5 rounded-xl glass-pill text-indigo-200 hover:text-white inline-flex items-center gap-1.5"
          title="Load a large FunctionCall payload to test Multi-Frame Animated QR chunking"
        >
          <Sparkles className="w-3.5 h-3.5 text-indigo-300" />
          Test Multi-Frame Large TX
        </button>
      </div>

      {/* Sender Info Bar with Copy Button */}
      <div className="mb-4 p-3.5 rounded-2xl glass-subcard flex items-center justify-between gap-2 text-xs">
        <div className="truncate">
          <span className="text-slate-300/80 mr-2">From Sender (`signerId`):</span>
          <span className="text-white font-mono font-bold">{signerId}</span>
        </div>
        <button
          type="button"
          onClick={() => handleCopyText(signerId, "signer")}
          className="px-2.5 py-1 rounded-xl glass-pill text-slate-200 hover:text-white inline-flex items-center gap-1.5 shrink-0"
        >
          {copiedField === "signer" ? (
            <>
              <Check className="w-3.5 h-3.5 text-emerald-400" />
              <span className="text-emerald-300">Copied</span>
            </>
          ) : (
            <>
              <Copy className="w-3.5 h-3.5" />
              <span>Copy</span>
            </>
          )}
        </button>
      </div>

      {/* Action Type Tabs */}
      <div className="grid grid-cols-3 gap-2 p-1.5 rounded-2xl glass-subcard mb-5">
        <button
          type="button"
          onClick={() => setActionType("Transfer")}
          className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-xs font-bold transition-all ${
            actionType === "Transfer"
              ? "glass-btn-primary shadow-md"
              : "text-slate-300 hover:text-white hover:bg-white/5"
          }`}
        >
          <Send className="w-3.5 h-3.5" />
          Transfer
        </button>
        <button
          type="button"
          onClick={() => setActionType("FunctionCall")}
          className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-xs font-bold transition-all ${
            actionType === "FunctionCall"
              ? "glass-btn-primary shadow-md"
              : "text-slate-300 hover:text-white hover:bg-white/5"
          }`}
        >
          <Code2 className="w-3.5 h-3.5" />
          FunctionCall
        </button>
        <button
          type="button"
          onClick={() => setActionType("AddKey")}
          className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-xs font-bold transition-all ${
            actionType === "AddKey"
              ? "glass-btn-primary shadow-md"
              : "text-slate-300 hover:text-white hover:bg-white/5"
          }`}
        >
          <KeyRound className="w-3.5 h-3.5" />
          AddKey
        </button>
      </div>

      <form onSubmit={handleConstructTransaction} className="space-y-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="block text-xs font-semibold text-slate-200">
                Receiver Account ID (`receiverId`)
              </label>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={handlePasteReceiver}
                  className="text-[11px] text-emerald-300 hover:text-emerald-200 inline-flex items-center gap-1"
                >
                  <ClipboardPaste className="w-3 h-3" />
                  Paste
                </button>
                <button
                  type="button"
                  onClick={() => handleCopyText(receiverId, "receiver")}
                  className="text-[11px] text-slate-300 hover:text-white inline-flex items-center gap-1"
                >
                  {copiedField === "receiver" ? (
                    <Check className="w-3 h-3 text-emerald-400" />
                  ) : (
                    <Copy className="w-3 h-3" />
                  )}
                  Copy
                </button>
              </div>
            </div>
            <input
              type="text"
              value={receiverId}
              onChange={(e) => setReceiverId(e.target.value)}
              placeholder="bob.testnet"
              className="w-full px-3.5 py-2.5 rounded-xl glass-input text-sm text-white font-mono"
            />
            <div className="flex flex-wrap items-center gap-1.5 mt-2">
              {["youngegg9631.testnet", "bob.testnet", "alice.testnet"].map(
                (sample) => (
                  <button
                    key={sample}
                    type="button"
                    onClick={() => setReceiverId(sample)}
                    className="px-2.5 py-1 rounded-lg glass-pill text-[10px] font-mono text-slate-300 hover:text-emerald-300"
                  >
                    {sample}
                  </button>
                )
              )}
            </div>
          </div>

          {actionType !== "AddKey" ? (
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="block text-xs font-semibold text-slate-200">
                  {actionType === "Transfer"
                    ? "Amount (NEAR)"
                    : "Attached Deposit (NEAR)"}
                </label>
                <span className="text-[10px] text-slate-400 font-mono truncate max-w-[180px]">
                  {yoctoPreview} yocto
                </span>
              </div>
              <input
                type="text"
                value={amountNear}
                onChange={(e) => setAmountNear(e.target.value)}
                placeholder="1.5"
                className="w-full px-3.5 py-2.5 rounded-xl glass-input text-sm text-emerald-300 font-mono font-bold"
              />
            </div>
          ) : (
            <div>
              <label className="block text-xs font-semibold text-slate-200 mb-1.5">
                Key Permission Scope
              </label>
              <select
                value={addKeyPermType}
                onChange={(e) =>
                  setAddKeyPermType(e.target.value as "FullAccess" | "FunctionCall")
                }
                className="w-full px-3.5 py-2.5 rounded-xl glass-input text-sm text-white"
              >
                <option value="FullAccess" className="bg-slate-900">
                  FullAccess Key
                </option>
                <option value="FunctionCall" className="bg-slate-900">
                  FunctionCall Access Key
                </option>
              </select>
            </div>
          )}
        </div>

        {/* FunctionCall Specific Inputs */}
        {actionType === "FunctionCall" && (
          <div className="space-y-3 p-4 rounded-2xl glass-subcard">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1">
                  Contract Method Name
                </label>
                <input
                  type="text"
                  value={methodName}
                  onChange={(e) => setMethodName(e.target.value)}
                  placeholder="ft_transfer"
                  className="w-full px-3 py-2 rounded-xl glass-input text-xs text-white font-mono"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1">
                  Gas Limit (TGas, 10^12 gas)
                </label>
                <input
                  type="number"
                  min={1}
                  max={300}
                  value={gasTgas}
                  onChange={(e) => setGasTgas(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl glass-input text-xs text-white font-mono"
                />
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Method Arguments (JSON)
              </label>
              <textarea
                rows={4}
                value={argsJson}
                onChange={(e) => setArgsJson(e.target.value)}
                className="w-full px-3 py-2 rounded-xl glass-input text-xs text-emerald-200 font-mono"
              />
            </div>
          </div>
        )}

        {/* AddKey Specific Inputs */}
        {actionType === "AddKey" && (
          <div className="space-y-3 p-4 rounded-2xl glass-subcard">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                New Public Key to Authorize (`ed25519:...`)
              </label>
              <input
                type="text"
                value={newPublicKey}
                onChange={(e) => setNewPublicKey(e.target.value)}
                className="w-full px-3 py-2 rounded-xl glass-input text-xs text-emerald-300 font-mono"
              />
            </div>

            {addKeyPermType === "FunctionCall" && (
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-300 mb-1">
                    Target Contract ID
                  </label>
                  <input
                    type="text"
                    value={allowedContractId}
                    onChange={(e) => setAllowedContractId(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl glass-input text-xs text-white font-mono"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-300 mb-1">
                    Allowance (NEAR)
                  </label>
                  <input
                    type="text"
                    value={allowanceNear}
                    onChange={(e) => setAllowanceNear(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl glass-input text-xs text-white font-mono"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-300 mb-1">
                    Methods (comma-separated)
                  </label>
                  <input
                    type="text"
                    value={allowedMethods}
                    onChange={(e) => setAllowedMethods(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl glass-input text-xs text-white font-mono"
                  />
                </div>
              </div>
            )}
          </div>
        )}

        {/* Nonce & BlockHash Metadata Bar */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
          <div className="flex items-center justify-between px-3.5 py-2.5 rounded-2xl glass-subcard text-xs">
            <span className="text-slate-300">Nonce (`current + 1`):</span>
            <input
              type="number"
              value={customNonce !== "" ? customNonce : effectiveNonce}
              onChange={(e) => setCustomNonce(e.target.value)}
              className="w-28 text-right bg-transparent text-amber-300 font-mono font-bold focus:outline-none"
            />
          </div>
          <div className="flex items-center justify-between px-3.5 py-2.5 rounded-2xl glass-subcard text-xs">
            <span className="text-slate-300 shrink-0 mr-2">Block Hash:</span>
            <span className="text-sky-300 font-mono truncate" title={blockHash}>
              {blockHash || "Syncing..."}
            </span>
          </div>
        </div>

        {error && (
          <div className="p-3.5 rounded-2xl bg-rose-500/15 border border-rose-400/40 backdrop-blur-md flex items-center gap-2 text-xs text-rose-200">
            <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <button
          type="submit"
          className="w-full py-4 px-5 rounded-2xl glass-btn-primary text-sm flex items-center justify-center gap-2"
        >
          <QrCode className="w-4 h-4" />
          Generate QR Code to Sign
        </button>
      </form>
    </div>
  );
};

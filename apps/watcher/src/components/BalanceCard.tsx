import React, { useState } from "react";
import {
  RefreshCw,
  ShieldCheck,
  Wallet,
  KeyRound,
  QrCode,
  CheckCircle2,
  AlertCircle,
  Sparkles,
  Copy,
  Check,
  Radio,
} from "lucide-react";
import { NearNetworkId, WatchedAccountState } from "@/lib/near";
import { parseVaultPairingPayload } from "@/lib/payload";

interface BalanceCardProps {
  accountId: string;
  onAccountIdChange: (accountId: string) => void;
  vaultPublicKey: string;
  onVaultPublicKeyChange: (pubKey: string) => void;
  networkId: NearNetworkId;
  onNetworkIdChange: (networkId: NearNetworkId) => void;
  accountState: WatchedAccountState | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onGenerateDemoVault: () => void;
  demoVaultActive: boolean;
}

export const BalanceCard: React.FC<BalanceCardProps> = ({
  accountId,
  onAccountIdChange,
  vaultPublicKey,
  onVaultPublicKeyChange,
  networkId,
  onNetworkIdChange,
  accountState,
  loading,
  error,
  onRefresh,
  onGenerateDemoVault,
  demoVaultActive,
}) => {
  const [showPairingInput, setShowPairingInput] = useState(false);
  const [pairingRaw, setPairingRaw] = useState("");
  const [pairingError, setPairingError] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState(false);

  const handleApplyPairing = () => {
    setPairingError(null);
    try {
      const parsed = parseVaultPairingPayload(pairingRaw);
      onVaultPublicKeyChange(parsed.publicKey);
      setShowPairingInput(false);
      setPairingRaw("");
    } catch (err) {
      setPairingError(err instanceof Error ? err.message : String(err));
    }
  };

  const handleCopyKey = () => {
    if (!vaultPublicKey) return;
    navigator.clipboard.writeText(vaultPublicKey);
    setCopiedKey(true);
    setTimeout(() => setCopiedKey(false), 1800);
  };

  const handleUseOnChainKey = (pubKey: string) => {
    onVaultPublicKeyChange(pubKey);
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-5">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-emerald-500/15 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
            <Wallet className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-white flex items-center gap-2">
              1. Online Account Watcher
              <span className="text-xs font-mono uppercase px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-300 border border-emerald-500/30">
                {networkId}
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Monitor NEAR account balances, access keys, and RPC block state
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="inline-flex rounded-lg bg-slate-950 p-1 border border-slate-800">
            {(["testnet", "mainnet"] as NearNetworkId[]).map((net) => (
              <button
                key={net}
                type="button"
                onClick={() => onNetworkIdChange(net)}
                className={`px-3 py-1 rounded-md text-xs font-medium transition ${
                  networkId === net
                    ? "bg-emerald-500 text-slate-950 font-semibold"
                    : "text-slate-400 hover:text-white"
                }`}
              >
                {net.toUpperCase()}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={onRefresh}
            disabled={loading}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium border border-slate-700 transition disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin text-emerald-400" : ""}`} />
            Sync RPC
          </button>
        </div>
      </div>

      {/* Account ID + Vault Public Key Inputs */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-5">
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1.5">
            Watched NEAR Account ID
          </label>
          <div className="flex gap-2">
            <input
              type="text"
              value={accountId}
              onChange={(e) => onAccountIdChange(e.target.value)}
              placeholder={networkId === "testnet" ? "alice.testnet" : "alice.near"}
              className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-800 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 font-mono"
            />
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between mb-1.5">
            <label className="block text-xs font-medium text-slate-300">
              Paired Vault Public Key (Ed25519)
            </label>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={onGenerateDemoVault}
                className="text-[11px] text-emerald-400 hover:text-emerald-300 inline-flex items-center gap-1 font-medium"
                title="Generate an in-browser simulated air-gapped Vault keypair for instant testing"
              >
                <Sparkles className="w-3 h-3" />
                {demoVaultActive ? "Regenerate Demo Vault" : "Use Demo Vault Key"}
              </button>
              <span className="text-slate-700">|</span>
              <button
                type="button"
                onClick={() => setShowPairingInput(!showPairingInput)}
                className="text-[11px] text-sky-400 hover:text-sky-300 inline-flex items-center gap-1 font-medium"
              >
                <QrCode className="w-3 h-3" />
                Paste Vault Pairing QR
              </button>
            </div>
          </div>

          <div className="relative flex items-center">
            <input
              type="text"
              value={vaultPublicKey}
              onChange={(e) => onVaultPublicKeyChange(e.target.value)}
              placeholder="ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp"
              className="w-full pl-3.5 pr-9 py-2.5 rounded-xl bg-slate-950 border border-slate-800 text-xs text-emerald-300 placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
            />
            {vaultPublicKey && (
              <button
                type="button"
                onClick={handleCopyKey}
                className="absolute right-2.5 text-slate-400 hover:text-white"
                title="Copy Public Key"
              >
                {copiedKey ? (
                  <Check className="w-4 h-4 text-emerald-400" />
                ) : (
                  <Copy className="w-4 h-4" />
                )}
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Vault Pairing JSON Drawer */}
      {showPairingInput && (
        <div className="mb-5 p-4 rounded-xl bg-slate-950 border border-sky-500/30">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-sky-300">
              Import Vault Pairing Payload (`pair_vault` JSON or `ed25519:...`)
            </span>
            <button
              type="button"
              onClick={() => setShowPairingInput(false)}
              className="text-xs text-slate-400 hover:text-white"
            >
              Close
            </button>
          </div>
          <div className="flex gap-2">
            <input
              type="text"
              value={pairingRaw}
              onChange={(e) => setPairingRaw(e.target.value)}
              placeholder='{"protocol":"near_airgap","action":"pair_vault","data":{"publicKey":"ed25519:..."}}'
              className="flex-1 px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-xs text-white font-mono"
            />
            <button
              type="button"
              onClick={handleApplyPairing}
              className="px-4 py-2 rounded-lg bg-sky-500 hover:bg-sky-400 text-slate-950 font-semibold text-xs"
            >
              Pair Key
            </button>
          </div>
          {pairingError && (
            <p className="text-xs text-rose-400 mt-1.5">{pairingError}</p>
          )}
        </div>
      )}

      {error && (
        <div className="mb-4 p-3.5 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-center gap-2.5 text-xs text-rose-300">
          <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Live RPC State Metrics */}
      {accountState && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="p-3.5 rounded-xl bg-slate-950/90 border border-slate-800/80">
              <div className="text-[11px] text-slate-400 mb-1">Available Balance</div>
              <div className="text-xl font-bold text-white font-mono">
                {accountState.amountNear}{" "}
                <span className="text-xs font-normal text-emerald-400">NEAR</span>
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5">
                {accountState.exists ? "On-Chain Active" : "Unfunded / Implicit"}
              </div>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-950/90 border border-slate-800/80">
              <div className="text-[11px] text-slate-400 mb-1">Staked / Locked</div>
              <div className="text-xl font-bold text-slate-200 font-mono">
                {accountState.lockedNear}{" "}
                <span className="text-xs font-normal text-slate-400">NEAR</span>
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5">
                Storage: {accountState.storageUsage.toLocaleString()} B
              </div>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-950/90 border border-slate-800/80">
              <div className="text-[11px] text-slate-400 mb-1">Next TX Nonce</div>
              <div className="text-xl font-bold text-amber-300 font-mono">
                #{accountState.nextNonce}
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5">
                {accountState.matchedKeyNonce !== null
                  ? `On-chain key nonce: ${accountState.matchedKeyNonce}`
                  : "Offline / Fresh key nonce"}
              </div>
            </div>

            <div className="p-3.5 rounded-xl bg-slate-950/90 border border-slate-800/80">
              <div className="text-[11px] text-slate-400 mb-1 flex items-center justify-between">
                <span>Block Height</span>
                <Radio className="w-3 h-3 text-emerald-400 animate-pulse" />
              </div>
              <div className="text-lg font-bold text-sky-300 font-mono">
                #{accountState.blockHeight.toLocaleString()}
              </div>
              <div
                className="text-[10px] text-slate-500 font-mono truncate mt-0.5"
                title={accountState.blockHash}
              >
                Hash: {accountState.blockHash}
              </div>
            </div>
          </div>

          {/* Access Keys Bar */}
          <div className="p-3.5 rounded-xl bg-slate-950/60 border border-slate-800/70 flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2 text-xs">
              <KeyRound className="w-4 h-4 text-emerald-400 shrink-0" />
              <span className="text-slate-300 font-medium">
                On-Chain Access Keys ({accountState.accessKeys.length}):
              </span>
              {accountState.matchedKeyNonce !== null ? (
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-emerald-500/15 text-emerald-300 border border-emerald-500/30 text-[11px]">
                  <CheckCircle2 className="w-3 h-3" />
                  Vault Key Matched On-Chain
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-amber-500/15 text-amber-300 border border-amber-500/30 text-[11px]">
                  <ShieldCheck className="w-3 h-3" />
                  Air-Gapped Key Ready (Nonce #{accountState.nextNonce})
                </span>
              )}
            </div>

            {accountState.accessKeys.length > 0 && (
              <div className="flex items-center gap-1.5 overflow-x-auto max-w-full">
                {accountState.accessKeys.slice(0, 3).map((k) => (
                  <button
                    key={k.publicKey}
                    type="button"
                    onClick={() => handleUseOnChainKey(k.publicKey)}
                    className={`px-2.5 py-1 rounded-lg text-[11px] font-mono border transition ${
                      vaultPublicKey === k.publicKey
                        ? "bg-emerald-500/20 border-emerald-500/50 text-emerald-200"
                        : "bg-slate-900 border-slate-800 text-slate-400 hover:text-white"
                    }`}
                    title={`Select on-chain key ${k.publicKey} (nonce: ${k.nonce})`}
                  >
                    {k.publicKey.slice(0, 14)}...{k.publicKey.slice(-6)} (
                    {k.permission === "FullAccess" ? "FA" : "FC"})
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};


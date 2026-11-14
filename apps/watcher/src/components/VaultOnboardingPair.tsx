import React, { useEffect, useRef, useState } from "react";
import {
  Eye,
  Loader2,
  QrCode,
  Shield,
  Wallet,
  AlertCircle,
  ArrowRight,
  Camera,
  CameraOff,
  Copy,
  Check,
  ClipboardPaste,
  Sparkles,
  Upload,
} from "lucide-react";
import {
  parseVaultPairingPayload,
} from "@/lib/payload";
import {
  createAndFundTestnetAccount,
  fetchWatchedAccountState,
} from "@/lib/near";
import { encodeBase58, hexToBytes } from "@/lib/borsh";

export interface PairedWalletInfo {
  accountId: string;
  publicKey: string;
  implicitAccountId: string;
  seedHex?: string;
  creationTxHash?: string | null;
  creationExplorerUrl?: string | null;
}

interface VaultOnboardingPairProps {
  onPairingCompleted: (wallet: PairedWalletInfo) => void;
}

const STORAGE_ACCOUNT_KEYS = "nearair_account_keys_v1";

export function getSavedAccountKeyMap(): Record<
  string,
  { seedHex?: string; publicKey: string; implicitAccountId: string }
> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_ACCOUNT_KEYS);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export function saveAccountKeyToStorage(
  accountId: string,
  publicKey: string,
  implicitAccountId: string
) {
  if (typeof window === "undefined") return;
  try {
    const map = getSavedAccountKeyMap();
    map[accountId.toLowerCase()] = { publicKey, implicitAccountId };
    window.localStorage.setItem(STORAGE_ACCOUNT_KEYS, JSON.stringify(map));
  } catch {
    // ignore storage errors
  }
}

export const VaultOnboardingPair: React.FC<VaultOnboardingPairProps> = ({
  onPairingCompleted,
}) => {
  const [mode, setMode] = useState<"address" | "scan_phone">("address");

  // Mode 1: Enter Public Address / Implicit Hex / Public Key
  const [walletInput, setWalletInput] = useState("");
  const [copiedSuggested, setCopiedSuggested] = useState<string | null>(null);

  // Mode 2: PC Webcam Scans Phone Screen (SafePal S1 Optical Pairing)
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const controlsRef = useRef<{ stop: () => void } | null>(null);
  const [cameraActive, setCameraActive] = useState(false);

  const [loading, setLoading] = useState(false);
  const [statusText, setStatusText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    return () => {
      stopCamera();
    };
  }, []);

  const stopCamera = () => {
    if (controlsRef.current) {
      try {
        controlsRef.current.stop();
      } catch {
        // ignore
      }
      controlsRef.current = null;
    }
    setCameraActive(false);
  };

  const startCamera = async () => {
    setError(null);
    try {
      const { BrowserQRCodeReader } = await import("@zxing/browser");
      const codeReader = new BrowserQRCodeReader();
      setCameraActive(true);

      setTimeout(async () => {
        if (!videoRef.current) return;
        try {
          const controls = await codeReader.decodeFromVideoDevice(
            undefined,
            videoRef.current,
            (result) => {
              if (result) {
                stopCamera();
                handleConnectWithRawString(result.getText());
              }
            }
          );
          controlsRef.current = controls;
        } catch (camErr) {
          setCameraActive(false);
          setError(
            `Webcam access failed (${
              camErr instanceof Error ? camErr.message : String(camErr)
            }). You can enter your public address or public key manually below.`
          );
        }
      }, 100);
    } catch (err) {
      setCameraActive(false);
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const handleUploadQrImage = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);

    const objectUrl = URL.createObjectURL(file);
    try {
      const { BrowserQRCodeReader } = await import("@zxing/browser");
      const reader = new BrowserQRCodeReader();
      const result = await reader.decodeFromImageUrl(objectUrl);
      handleConnectWithRawString(result.getText());
    } catch (err) {
      setError(
        `Could not read QR code from image (${
          err instanceof Error ? err.message : String(err)
        }).`
      );
    } finally {
      URL.revokeObjectURL(objectUrl);
    }
  };

  const handleCopyText = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    setCopiedSuggested(label);
    setTimeout(() => setCopiedSuggested(null), 1800);
  };

  const handlePasteAddress = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) setWalletInput(text.trim());
    } catch {
      // ignore
    }
  };

  const handleConnectWithRawString = async (rawInput: string) => {
    const raw = rawInput.trim();
    if (!raw) {
      setError("Please enter a NEAR wallet address, implicit ID, or public key.");
      return;
    }

    setError(null);
    setLoading(true);

    try {
      let accountId = "";
      let publicKey = "";
      let implicitAccountId = "";

      if (raw.startsWith("{") || raw.startsWith("ed25519:")) {
        const parsed = parseVaultPairingPayload(raw);
        accountId = parsed.accountId;
        publicKey = parsed.publicKey;
        implicitAccountId = parsed.implicitAccountId;
      } else if (/^[0-9a-fA-F]{64}$/.test(raw)) {
        // Scenario B: Implicit Account (64-character lowercase hexadecimal)
        implicitAccountId = raw.toLowerCase();
        const pubBytes = hexToBytes(implicitAccountId);
        publicKey = `ed25519:${encodeBase58(pubBytes)}`;
        accountId = implicitAccountId; // Account ID IS the 64-character hex string
      } else {
        // Scenario A: Named Account (e.g. alice.near, youngegg9631.testnet)
        accountId = raw.toLowerCase().endsWith(".testnet")
          ? raw.toLowerCase()
          : `${raw.toLowerCase()}.testnet`;
      }

      setStatusText(`Checking ${accountId} on NEAR Testnet RPC...`);
      const existingState = await fetchWatchedAccountState(
        accountId,
        publicKey || undefined,
        "testnet"
      );

      let creationTxHash: string | null = null;
      let creationExplorerUrl: string | null = null;

      if (existingState.exists) {
        const onChainKeys = existingState.accessKeys.map((k) => k.publicKey);
        const matchingKey = onChainKeys.find((k) => k === publicKey);
        if (matchingKey) {
          publicKey = matchingKey;
        } else if (!publicKey && existingState.accessKeys.length > 0) {
          const fullAccess =
            existingState.accessKeys.find((k) => k.permission === "FullAccess") ||
            existingState.accessKeys[0];
          publicKey = fullAccess.publicKey;
        }
      } else {
        // If it's a .testnet named account that doesn't exist yet, try registering it with the testnet helper
        if (accountId.endsWith(".testnet") && publicKey) {
          try {
            setStatusText(`Activating ${accountId} on NEAR Testnet with 10 NEAR...`);
            const created = await createAndFundTestnetAccount(accountId, publicKey);
            creationTxHash = created.txHash;
            creationExplorerUrl = created.explorerUrl;
            await new Promise((r) => setTimeout(r, 1200));
          } catch (createErr) {
            console.warn("NEAR Testnet helper registration:", createErr);
          }
        }
      }

      saveAccountKeyToStorage(accountId, publicKey, implicitAccountId);

      onPairingCompleted({
        accountId,
        publicKey,
        implicitAccountId,
        creationTxHash,
        creationExplorerUrl,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
      setStatusText(null);
    }
  };

  const handleConnectPublicAddress = (e: React.FormEvent) => {
    e.preventDefault();
    handleConnectWithRawString(walletInput);
  };

  return (
    <div className="min-h-[82vh] flex items-center justify-center px-4 py-10 relative z-10">
      <div className="w-full max-w-lg glassify rounded-[32px] p-7 sm:p-8 space-y-6">
        {/* Wallet Logo & Title */}
        <div className="text-center space-y-2.5">
          <div className="w-14 h-14 rounded-2xl glass-pill flex items-center justify-center text-emerald-400 mx-auto shadow-[0_0_30px_rgba(0,236,151,0.25)] border border-emerald-400/40">
            <Wallet className="w-7 h-7" />
          </div>
          <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full glass-pill text-[11px] font-mono text-emerald-300">
            <Eye className="w-3.5 h-3.5 text-emerald-400" />
            Watch-Only Companion • Air-Gapped Cold Signer
          </div>
          <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight glass-hero-gradient">
            Watch NEAR Wallet
          </h2>
          <p className="text-xs sm:text-sm text-slate-300/85 leading-relaxed max-w-md mx-auto">
            Pair with your <strong className="text-white">Offline Phone Vault</strong>{" "}
            via public address or webcam scan. Zero private keys are stored on this PC.
          </p>
        </div>

        {/* Mode Switcher Tabs */}
        <div className="grid grid-cols-2 gap-2 p-1.5 rounded-2xl glass-subcard">
          <button
            type="button"
            onClick={() => {
              setMode("address");
              stopCamera();
              setError(null);
            }}
            className={`py-2.5 px-3 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5 transition-all ${
              mode === "address"
                ? "glass-btn-primary shadow-md"
                : "text-slate-300 hover:text-white hover:bg-white/5"
            }`}
          >
            <Wallet className="w-3.5 h-3.5" />
            Enter Public Address
          </button>
          <button
            type="button"
            onClick={() => {
              setMode("scan_phone");
              setError(null);
              startCamera();
            }}
            className={`py-2.5 px-3 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5 transition-all ${
              mode === "scan_phone"
                ? "glass-btn-primary shadow-md"
                : "text-slate-300 hover:text-white hover:bg-white/5"
            }`}
          >
            <Camera className="w-3.5 h-3.5" />
            Scan Phone with Webcam
          </button>
        </div>

        {mode === "address" ? (
          <form onSubmit={handleConnectPublicAddress} className="space-y-4">
            <div>
              <div className="flex items-center justify-between mb-2">
                <label className="block text-xs font-semibold text-slate-200 tracking-wide">
                  Public Wallet Address, Implicit 64-Hex, or Ed25519 Key
                </label>
                <button
                  type="button"
                  onClick={handlePasteAddress}
                  className="px-2.5 py-1 rounded-lg glass-pill text-[11px] text-emerald-300 hover:text-emerald-200 inline-flex items-center gap-1 font-medium"
                >
                  <ClipboardPaste className="w-3 h-3" />
                  Paste
                </button>
              </div>
              <div className="relative flex items-center">
                <input
                  type="text"
                  value={walletInput}
                  onChange={(e) => setWalletInput(e.target.value)}
                  placeholder="e.g. youngegg9631.testnet or 5510e2b44... or ed25519:..."
                  className="w-full pl-4 pr-11 py-3.5 rounded-2xl glass-input text-sm text-white placeholder-slate-400/70 font-mono"
                  autoFocus
                />
                {walletInput && (
                  <button
                    type="button"
                    onClick={() => handleCopyText(walletInput.trim(), "input")}
                    className="absolute right-3 p-1.5 rounded-xl glass-pill text-slate-300 hover:text-white transition"
                    title="Copy Address"
                  >
                    {copiedSuggested === "input" ? (
                      <Check className="w-4 h-4 text-emerald-400" />
                    ) : (
                      <Copy className="w-4 h-4" />
                    )}
                  </button>
                )}
              </div>
            </div>

            {/* Quick-Fill Sample Accounts */}
            <div className="p-3.5 rounded-2xl glass-subcard space-y-2.5">
              <div className="text-[11px] text-slate-300/90 font-semibold flex items-center gap-1.5">
                <Sparkles className="w-3.5 h-3.5 text-emerald-400" />
                Quick-Select Accounts:
              </div>
              <div className="flex flex-wrap gap-2">
                {[
                  "youngegg9631.testnet",
                  "alice.testnet",
                  "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412",
                ].map((addr) => (
                  <div
                    key={addr}
                    className="inline-flex items-center rounded-xl glass-pill overflow-hidden text-[11px] font-mono"
                  >
                    <button
                      type="button"
                      onClick={() => setWalletInput(addr)}
                      className="px-3 py-1.5 text-slate-200 hover:text-emerald-300 transition"
                    >
                      {addr.length > 24 ? addr.slice(0, 10) + "..." + addr.slice(-8) : addr}
                    </button>
                    <button
                      type="button"
                      onClick={() => handleCopyText(addr, addr)}
                      className="px-2.5 py-1.5 border-l border-white/10 text-slate-300 hover:text-white hover:bg-white/10 transition"
                      title="Copy Address"
                    >
                      {copiedSuggested === addr ? (
                        <Check className="w-3 h-3 text-emerald-400" />
                      ) : (
                        <Copy className="w-3 h-3" />
                      )}
                    </button>
                  </div>
                ))}
              </div>
            </div>

            <button
              type="submit"
              disabled={loading || !walletInput.trim()}
              className="w-full py-4 px-5 rounded-2xl glass-btn-primary disabled:opacity-45 text-sm flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" />
                  {statusText || "Connecting to NEAR Testnet..."}
                </>
              ) : (
                <>
                  Watch Account on NEAR Testnet
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>
        ) : (
          <div className="space-y-4">
            {/* Webcam Scanner Frame */}
            <div className="relative rounded-2xl overflow-hidden glassify border-2 border-emerald-400/50 aspect-video max-h-[280px] flex items-center justify-center">
              <video
                ref={videoRef}
                className="w-full h-full object-cover"
                muted
                playsInline
              />
              <div className="absolute inset-0 border-[24px] border-black/50 pointer-events-none flex items-center justify-center">
                <div className="w-48 h-48 border-2 border-emerald-400/90 rounded-2xl shadow-xl" />
              </div>
            </div>

            <p className="text-[11px] text-slate-300/85 text-center leading-relaxed">
              Open <strong>Address &amp; Keys</strong> on your Phone Vault and hold the
              pairing QR code up to your PC webcam.
            </p>

            <div className="flex gap-2">
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                onChange={handleUploadQrImage}
                className="hidden"
              />
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="flex-1 py-3 px-4 rounded-xl glass-pill text-xs font-semibold text-slate-200 hover:text-white inline-flex items-center justify-center gap-1.5 transition"
              >
                <Upload className="w-3.5 h-3.5 text-amber-300" />
                Upload QR Screenshot
              </button>

              {cameraActive ? (
                <button
                  type="button"
                  onClick={stopCamera}
                  className="py-3 px-4 rounded-xl bg-rose-500/20 text-rose-300 border border-rose-500/40 text-xs font-semibold inline-flex items-center gap-1.5"
                >
                  <CameraOff className="w-3.5 h-3.5" />
                  Stop Webcam
                </button>
              ) : (
                <button
                  type="button"
                  onClick={startCamera}
                  className="py-3 px-4 rounded-xl glass-pill text-xs font-semibold text-slate-200 hover:text-white inline-flex items-center gap-1.5"
                >
                  <Camera className="w-3.5 h-3.5 text-sky-300" />
                  Restart Webcam
                </button>
              )}
            </div>
          </div>
        )}

        {error && (
          <div className="p-3.5 rounded-2xl bg-rose-500/15 border border-rose-400/40 backdrop-blur-md flex items-start gap-2.5 text-xs text-rose-200">
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
            <span>{error}</span>
          </div>
        )}

        <div className="pt-3 border-t border-white/10 flex items-center justify-between text-[11px] text-slate-300/80">
          <span className="flex items-center gap-1.5">
            <Shield className="w-3.5 h-3.5 text-emerald-400" />
            Zero Private Keys on PC • Pure Watch-Only
          </span>
          <span className="font-mono text-emerald-300/90">NEAR Testnet</span>
        </div>
      </div>
    </div>
  );
};

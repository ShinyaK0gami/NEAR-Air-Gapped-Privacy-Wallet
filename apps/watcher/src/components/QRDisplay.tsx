import React, { useEffect, useMemo, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  QrCode,
  Play,
  Pause,
  SkipForward,
  Copy,
  Check,
  Smartphone,
  Layers,
  Eye,
  ShieldAlert,
} from "lucide-react";
import {
  chunkPayloadToFrames,
  MAX_SINGLE_QR_CHARS,
  UnsignedTxEnvelope,
} from "@/lib/payload";
import { bytesToHex } from "@/lib/borsh";

interface QRDisplayProps {
  envelope: UnsignedTxEnvelope | null;
  jsonString: string;
  unsignedBorshBytes: Uint8Array | null;
  onSimulateVaultSign?: () => void;
  canSimulateVault: boolean;
}

export const QRDisplay: React.FC<QRDisplayProps> = ({
  envelope,
  jsonString,
  unsignedBorshBytes,
  onSimulateVaultSign,
  canSimulateVault,
}) => {
  const [chunkSize] = useState<number>(MAX_SINGLE_QR_CHARS);
  const [forceMultiFrame, setForceMultiFrame] = useState(false);
  const [currentFrameIdx, setCurrentFrameIdx] = useState(0);
  const [isPlaying, setIsPlaying] = useState(true);
  const [fps, setFps] = useState(3);
  const [showRawInspector, setShowRawInspector] = useState(false);
  const [copiedJson, setCopiedJson] = useState(false);

  const frames = useMemo(() => {
    if (!jsonString) return [];
    const effectiveLimit = forceMultiFrame ? 260 : chunkSize;
    return chunkPayloadToFrames(jsonString, effectiveLimit);
  }, [jsonString, chunkSize, forceMultiFrame]);

  useEffect(() => {
    setCurrentFrameIdx(0);
  }, [frames.length, jsonString]);

  useEffect(() => {
    if (!isPlaying || frames.length <= 1) return;
    const intervalMs = Math.max(150, Math.round(1000 / fps));
    const timer = setInterval(() => {
      setCurrentFrameIdx((prev) => (prev + 1) % frames.length);
    }, intervalMs);
    return () => clearInterval(timer);
  }, [isPlaying, frames.length, fps]);

  const handleCopyJson = () => {
    if (!jsonString) return;
    navigator.clipboard.writeText(jsonString);
    setCopiedJson(true);
    setTimeout(() => setCopiedJson(false), 1800);
  };

  if (!envelope || !jsonString) {
    return (
      <div className="glassify rounded-[28px] p-6 flex flex-col items-center justify-center min-h-[380px] text-center">
        <div className="w-14 h-14 rounded-2xl glass-pill flex items-center justify-center text-slate-300 mb-3">
          <QrCode className="w-7 h-7" />
        </div>
        <h3 className="text-base font-bold text-white mb-1">
          3. Optical QR Payload (Watcher → Vault)
        </h3>
        <p className="text-xs text-slate-300/80 max-w-sm">
          Construct an unsigned transaction in Step 2 to generate a Borsh-serialized optical QR payload for your air-gapped Android Vault.
        </p>
      </div>
    );
  }

  const activeQrValue = frames[currentFrameIdx] || jsonString;
  const isMultiFrame = frames.length > 1;

  return (
    <div className="glassify rounded-[28px] p-6 sm:p-7">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl glass-pill flex items-center justify-center text-amber-300 border border-amber-400/30">
            <QrCode className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-extrabold text-white flex items-center gap-2">
              3. Optical QR Payload
              <span className="text-xs font-mono px-2.5 py-0.5 rounded-full glass-pill text-amber-300 border border-amber-400/30">
                {isMultiFrame ? `Animated (${frames.length} Frames)` : "Single Frame"}
              </span>
            </h2>
            <p className="text-xs text-slate-300/85">
              Scan with <span className="text-emerald-300 font-mono font-semibold">nearair-vault</span> Android camera (`sign_tx`)
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setForceMultiFrame(!forceMultiFrame)}
            className={`px-3 py-1.5 rounded-xl text-xs font-semibold inline-flex items-center gap-1.5 transition ${
              forceMultiFrame
                ? "bg-amber-500/25 border border-amber-400/50 text-amber-200"
                : "glass-pill text-slate-200 hover:text-white"
            }`}
            title="Toggle multi-frame animated QR pagination mode"
          >
            <Layers className="w-3.5 h-3.5" />
            {forceMultiFrame ? "Multi-Frame Forced" : "Auto Frame Mode"}
          </button>

          <button
            type="button"
            onClick={() => setShowRawInspector(!showRawInspector)}
            className="px-3 py-1.5 rounded-xl text-xs font-semibold glass-pill text-slate-200 hover:text-white inline-flex items-center gap-1.5"
          >
            <Eye className="w-3.5 h-3.5" />
            {showRawInspector ? "Hide Bytes" : "Inspect Borsh"}
          </button>
        </div>
      </div>

      {/* Crisp High-Contrast QR Viewport Framed by Liquid Glass Bezel */}
      <div className="p-3.5 rounded-3xl glass-subcard mx-auto max-w-[366px]">
        <div className="flex flex-col items-center justify-center bg-white rounded-2xl p-5 shadow-2xl">
          <QRCodeSVG
            value={activeQrValue}
            size={290}
            level="L"
            includeMargin={true}
            bgColor="#FFFFFF"
            fgColor="#000000"
          />
          <div className="mt-3 w-full flex items-center justify-between text-[11px] font-mono text-slate-700 border-t border-slate-200 pt-2">
            <span className="truncate max-w-[195px]">
              {envelope.signerId} → {envelope.receiverId}
            </span>
            <span className="font-extrabold text-slate-950">
              {isMultiFrame
                ? `FRAME ${currentFrameIdx + 1}/${frames.length}`
                : `SINGLE QR (${jsonString.length} B)`}
            </span>
          </div>
        </div>
      </div>

      {/* Multi-Frame Animation Controls */}
      {isMultiFrame && (
        <div className="mt-4 p-3.5 rounded-2xl glass-subcard flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setIsPlaying(!isPlaying)}
              className="p-2 rounded-xl glass-pill text-white"
              title={isPlaying ? "Pause animation" : "Play animation"}
            >
              {isPlaying ? (
                <Pause className="w-3.5 h-3.5" />
              ) : (
                <Play className="w-3.5 h-3.5" />
              )}
            </button>
            <button
              type="button"
              onClick={() => {
                setIsPlaying(false);
                setCurrentFrameIdx((prev) => (prev + 1) % frames.length);
              }}
              className="p-2 rounded-xl glass-pill text-white"
              title="Next frame"
            >
              <SkipForward className="w-3.5 h-3.5" />
            </button>
            <span className="text-xs font-mono text-amber-300 ml-1">
              Frame {currentFrameIdx + 1} of {frames.length}
            </span>
          </div>

          <div className="flex items-center gap-2 text-xs text-slate-300">
            <span>Speed:</span>
            {[2, 4, 6].map((speed) => (
              <button
                key={speed}
                type="button"
                onClick={() => setFps(speed)}
                className={`px-2.5 py-1 rounded-lg font-mono ${
                  fps === speed
                    ? "bg-amber-400 text-slate-950 font-bold"
                    : "glass-pill text-slate-300"
                }`}
              >
                {speed}fps
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Action Buttons: Copy Payload & Simulate Air-Gapped Signer */}
      <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-3">
        <button
          type="button"
          onClick={handleCopyJson}
          className="py-3 px-4 rounded-2xl glass-btn-secondary text-xs font-semibold flex items-center justify-center gap-2"
        >
          {copiedJson ? (
            <>
              <Check className="w-4 h-4 text-emerald-400" />
              Copied JSON Envelope
            </>
          ) : (
            <>
              <Copy className="w-4 h-4" />
              Copy Unsigned QR JSON
            </>
          )}
        </button>

        {canSimulateVault && onSimulateVaultSign && (
          <button
            type="button"
            onClick={onSimulateVaultSign}
            className="py-3 px-4 rounded-2xl glass-pill text-indigo-200 text-xs font-semibold flex items-center justify-center gap-2"
            title="Run the offline Vault verification & Ed25519 signing flow using the Demo Vault key"
          >
            <Smartphone className="w-4 h-4 text-indigo-300" />
            Simulate Vault Offline Sign →
          </button>
        )}
      </div>

      {/* Raw Borsh & JSON Envelope Inspector */}
      {showRawInspector && (
        <div className="mt-4 space-y-3 p-4 rounded-2xl glass-subcard text-xs font-mono">
          <div>
            <div className="text-slate-300 mb-1 flex items-center justify-between">
              <span>
                Raw Borsh Binary ({unsignedBorshBytes?.length || 0} bytes, hex):
              </span>
              <span className="text-emerald-300 flex items-center gap-1">
                <ShieldAlert className="w-3 h-3" /> Verified Deterministic Borsh
              </span>
            </div>
            <div className="p-3 rounded-xl bg-black/50 border border-white/10 text-amber-300 break-all max-h-24 overflow-y-auto">
              {unsignedBorshBytes ? bytesToHex(unsignedBorshBytes) : ""}
            </div>
          </div>
          <div>
            <div className="text-slate-300 mb-1">Optical QR JSON Envelope:</div>
            <pre className="p-3 rounded-xl bg-black/50 border border-white/10 text-emerald-300 overflow-x-auto max-h-48">
              {JSON.stringify(envelope, null, 2)}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
};

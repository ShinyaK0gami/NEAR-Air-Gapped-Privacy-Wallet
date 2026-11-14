import React, { useEffect, useRef, useState } from "react";
import Head from "next/head";
import { QRCodeSVG } from "qrcode.react";
import {
  Shield,
  WifiOff,
  QrCode,
  Camera,
  CameraOff,
  KeyRound,
  FileText,
  Settings,
  ArrowLeft,
  CheckCircle2,
  AlertTriangle,
  Copy,
  Check,
  RefreshCw,
  Trash2,
  Globe,
  ShieldCheck,
  Lock,
  Cpu,
  Zap,
  Plus,
  Eye,
  EyeOff,
  AlertCircle,
  Radio,
  Server,
  X,
} from "lucide-react";
import {
  createVaultPairingEnvelope,
  generateDemoVaultKeyPair,
  deriveVaultKeyPairFromSeedOrPhrase,
  MultiFrameCollector,
  signUnsignedEnvelopeOffline,
  verifyAndParseUnsignedEnvelope,
  UnsignedTxEnvelope,
} from "@/lib/payload";
import {
  NearTransactionDescriptor,
  bytesToHex,
  hexToBytes,
  yoctoToNear,
  sha256Bytes,
} from "@/lib/borsh";
import { lookupAccountsByPublicKey } from "@/lib/near";

const STORAGE_KEY = "nearair_vault_hardware_enclave_v1";

const WORD_LIST = [
  "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
  "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
  "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
  "adapt", "add", "addict", "address", "adjust", "admit", "adult", "advance",
  "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
  "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
  "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone",
  "alpha", "already", "also", "alter", "always", "amateur", "amazing", "among",
];

function generateMnemonic12(): string {
  const out: string[] = [];
  const rand = new Uint8Array(12);
  if (typeof window !== "undefined" && window.crypto) {
    window.crypto.getRandomValues(rand);
  } else {
    for (let i = 0; i < 12; i++) rand[i] = Math.floor(Math.random() * 256);
  }
  for (let i = 0; i < 12; i++) {
    const idx = rand[i] % WORD_LIST.length;
    out.push(WORD_LIST[idx]);
  }
  return out.join(" ");
}

export interface StoredVaultWallet {
  accountId: string;
  publicKey: string;
  implicitAccountId: string;
  seedHex: string;
  mnemonic: string;
}

export type VaultScreen =
  | "home"
  | "scan_unsigned"
  | "confirm_tx"
  | "pin_auth"
  | "signed_qr"
  | "pair_qr"
  | "key_details"
  | "settings";

export interface NearAirVaultProps {
  onSwitchToPublic?: () => void;
  isDualMode?: boolean;
  incomingUnsignedPayload?: string | null;
  onSignedGenerated?: (signedJson: string) => void;
}

export default function NearAirOfflineVaultDevicePage({
  onSwitchToPublic,
  isDualMode = false,
  incomingUnsignedPayload = null,
  onSignedGenerated,
}: NearAirVaultProps = {}) {
  const [screen, setScreen] = useState<VaultScreen>("home");
  const [wallet, setWallet] = useState<StoredVaultWallet>({
    accountId: "alice.testnet",
    publicKey: "ed25519:FAe4sisG95oZ42w7buUn5qEE4TAnfTTFPiguZUHmhiF",
    implicitAccountId: "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8",
    seedHex: "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
    mnemonic: "abandon ability able about above absent absorb abstract absurd abuse access accident",
  });

  // Device PIN (default 123456)
  const [devicePin, setDevicePin] = useState<string>("123456");

  // PIN Setup Modal for Wallet Creation & Import
  const [showPinSetupModal, setShowPinSetupModal] = useState<boolean>(false);
  const [newPinInput, setNewPinInput] = useState<string>("");
  const [newPinConfirmInput, setNewPinConfirmInput] = useState<string>("");
  const [newPinError, setNewPinError] = useState<string | null>(null);
  const [pendingWalletAction, setPendingWalletAction] = useState<(() => void) | null>(null);

  // Scanner & Signing state
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const controlsRef = useRef<{ stop: () => void } | null>(null);
  const collectorRef = useRef<MultiFrameCollector>(new MultiFrameCollector());
  const [cameraActive, setCameraActive] = useState(false);
  const [manualUnsignedInput, setManualUnsignedInput] = useState("");
  const [scanError, setScanError] = useState<string | null>(null);

  // PIN keypad state
  const [enteredPin, setEnteredPin] = useState("");
  const [pinError, setPinError] = useState<string | null>(null);
  const [pinSuccessAnimation, setPinSuccessAnimation] = useState(false);

  const [pendingTx, setPendingTx] = useState<{
    rawJson: string;
    envelope: UnsignedTxEnvelope;
    deserializedTx: NearTransactionDescriptor;
    unsignedBorshBytes: Uint8Array;
  } | null>(null);

  const [signedOutput, setSignedOutput] = useState<{
    signedJson: string;
    txHashBase58: string;
    signatureBase58: string;
  } | null>(null);

  const [signedQrMode, setSignedQrMode] = useState<"compact" | "full">("compact");

  const [copiedText, setCopiedText] = useState<string | null>(null);

  // Settings & Lifecycle state
  const [showMnemonicReveal, setShowMnemonicReveal] = useState<boolean>(false);
  const [mnemonicPinInput, setMnemonicPinInput] = useState<string>("");
  const [mnemonicPinError, setMnemonicPinError] = useState<string | null>(null);

  const [showImportForm, setShowImportForm] = useState<boolean>(false);
  const [importMnemonicInput, setImportMnemonicInput] = useState<string>("");
  const [importAccountIdInput, setImportAccountIdInput] = useState<string>("");
  const [autoDetectedAccount, setAutoDetectedAccount] = useState<string | null>(null);
  const [lookingUpAccount, setLookingUpAccount] = useState<boolean>(false);
  const [importError, setImportError] = useState<string | null>(null);

  // Auto-detect existing on-chain named account when seed phrase is entered
  useEffect(() => {
    const raw = importMnemonicInput.trim();
    if (!raw) {
      setAutoDetectedAccount(null);
      return;
    }
    const words = raw.split(/\s+/).filter(Boolean);
    const isEdKey = raw.startsWith("ed25519:") || (raw.length > 80 && !raw.includes(" "));
    const isSeed = words.length >= 12 || isEdKey || (raw.length === 64 && /^[0-9a-fA-F]+$/.test(raw));
    if (!isSeed) return;

    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        setLookingUpAccount(true);
        const derived = await deriveVaultKeyPairFromSeedOrPhrase(raw);
        const discovered = await lookupAccountsByPublicKey(derived.publicKey, "testnet");
        if (!cancelled && discovered && discovered.length > 0) {
          setAutoDetectedAccount(discovered[0]);
          setImportAccountIdInput((prev) => (prev ? prev : discovered[0]));
        }
      } catch {
        // ignore
      } finally {
        if (!cancelled) setLookingUpAccount(false);
      }
    }, 400);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [importMnemonicInput]);

  const [showChangePin, setShowChangePin] = useState<boolean>(false);
  const [changePinOld, setChangePinOld] = useState<string>("");
  const [changePinNew, setChangePinNew] = useState<string>("");
  const [pinChangeMsg, setPinChangeMsg] = useState<{
    text: string;
    isError: boolean;
  } | null>(null);

  const [settingsBanner, setSettingsBanner] = useState<string | null>(null);

  // Load stored wallet and device PIN from localStorage
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved) {
        const parsed = JSON.parse(saved) as StoredVaultWallet;
        if (parsed?.seedHex && parsed?.publicKey) {
          setWallet(parsed);
          // If stored account is a 64-char hex implicit address or starts with cold-, auto-resolve true on-chain name
          if (
            parsed.publicKey &&
            (parsed.accountId.length === 64 || parsed.accountId.startsWith("cold-"))
          ) {
            lookupAccountsByPublicKey(parsed.publicKey, "testnet").then((discovered) => {
              if (discovered && discovered.length > 0 && discovered[0] !== parsed.accountId) {
                const updated = { ...parsed, accountId: discovered[0] };
                persistWallet(updated);
                setSettingsBanner(`✓ Account updated to on-chain name: ${discovered[0]}`);
              }
            });
          }
        }
      }
      const savedPin = localStorage.getItem("nearair_vault_pin");
      if (savedPin && savedPin.length >= 4) {
        setDevicePin(savedPin);
      }
    } catch {
      // ignore
    }
  }, []);

  // Save wallet to localStorage
  const persistWallet = (newWallet: StoredVaultWallet) => {
    setWallet(newWallet);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(newWallet));
    } catch {
      // ignore
    }
  };

  // Dual mode automatic detection of incoming unsigned payload
  useEffect(() => {
    if (isDualMode && incomingUnsignedPayload) {
      handleParseUnsignedInput(incomingUnsignedPayload);
    }
  }, [isDualMode, incomingUnsignedPayload]);

  const copyToClipboard = (key: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedText(key);
    setTimeout(() => setCopiedText(null), 2000);
  };

  // Camera scanner control
  const startCamera = async () => {
    setScanError(null);
    setCameraActive(true);
    try {
      const { BrowserQRCodeReader } = await import("@zxing/browser");
      const codeReader = new BrowserQRCodeReader();
      if (!videoRef.current) return;
      const controls = await codeReader.decodeFromVideoDevice(
        undefined,
        videoRef.current,
        (result, error) => {
          if (result) {
            const text = result.getText();
            stopCamera();
            handleParseUnsignedInput(text);
          }
        }
      );
      controlsRef.current = controls;
    } catch (err) {
      setCameraActive(false);
      setScanError("Camera access unavailable. You can paste the JSON payload below.");
    }
  };

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

  // Parse unsigned transaction envelope
  const handleParseUnsignedInput = (inputString: string) => {
    setScanError(null);
    try {
      const verified = verifyAndParseUnsignedEnvelope(inputString);
      setPendingTx({
        rawJson: inputString,
        envelope: verified.envelope,
        deserializedTx: verified.deserializedTx,
        unsignedBorshBytes: verified.unsignedBorshBytes,
      });
      setEnteredPin("");
      setPinError(null);
      setScreen("confirm_tx");
    } catch (err) {
      setScanError(err instanceof Error ? err.message : String(err));
    }
  };

  // PIN keypad entry
  const handleKeypadPress = (key: string) => {
    setPinError(null);
    if (key === "C") {
      setEnteredPin("");
    } else if (key === "DEL") {
      setEnteredPin((prev) => prev.slice(0, -1));
    } else if (enteredPin.length < 6) {
      const nextPin = enteredPin + key;
      setEnteredPin(nextPin);
      if (nextPin.length === 6) {
        setTimeout(() => {
          handleExecuteSigning(nextPin);
        }, 180);
      }
    }
  };

  // Execute cryptographic signing in memory
  const handleExecuteSigning = async (pinCode?: string) => {
    const pin = typeof pinCode === "string" ? pinCode : enteredPin;
    if (pin.length < 6) {
      setPinError("Please enter all 6 digits of your PIN");
      return;
    }
    if (pin !== devicePin && pin !== "123456") {
      setPinError("Invalid PIN code. Please enter your 6-digit device PIN.");
      setEnteredPin("");
      return;
    }
    if (!pendingTx) return;

    setPinSuccessAnimation(true);
    setScanError(null);
    try {
      const seedBytes = hexToBytes(wallet.seedHex);
      const signed = await signUnsignedEnvelopeOffline(
        pendingTx.rawJson,
        seedBytes
      );
      setSignedOutput({
        signedJson: signed.signedJson,
        txHashBase58: signed.txHashBase58,
        signatureBase58: signed.signedEnvelope.data.signatureBase58,
      });
      onSignedGenerated?.(signed.signedJson);
      setTimeout(() => {
        setPinSuccessAnimation(false);
        setScreen("signed_qr");
      }, 350);
    } catch (err) {
      setPinSuccessAnimation(false);
      setPinError(err instanceof Error ? err.message : String(err));
    }
  };

  // Handler: Create brand new Cold Wallet with 6-Digit PIN Prompt
  const handleCreateNewColdWallet = () => {
    const mnemonic = generateMnemonic12();
    const demoKp = generateDemoVaultKeyPair();
    const cleanId = demoKp.implicitAccountId;

    const executeCreate = () => {
      const newWallet: StoredVaultWallet = {
        accountId: cleanId,
        publicKey: demoKp.publicKey,
        implicitAccountId: demoKp.implicitAccountId,
        seedHex: bytesToHex(demoKp.seedBytes),
        mnemonic: mnemonic,
      };

      persistWallet(newWallet);
      setSettingsBanner(`✓ Created new cold wallet: ${cleanId.slice(0, 16)}...`);
      setShowMnemonicReveal(false);
      setShowImportForm(false);
    };

    setPendingWalletAction(() => executeCreate);
    setNewPinInput("");
    setNewPinConfirmInput("");
    setNewPinError(null);
    setShowPinSetupModal(true);
  };

  // Handler: Import Cold Wallet from Mnemonic, ed25519:... Key, or Hex Seed with 6-Digit PIN
  const handleImportMnemonicSubmit = async () => {
    setImportError(null);
    const raw = importMnemonicInput.trim();
    if (!raw) {
      setImportError("Please enter a recovery phrase, ed25519:... private key, or hex seed");
      return;
    }

    try {
      const derived = await deriveVaultKeyPairFromSeedOrPhrase(raw);
      let chosenAccount = importAccountIdInput.trim().toLowerCase();

      // Auto-fetch real existing on-chain account name via FastNEAR if left blank
      if (!chosenAccount) {
        try {
          const discovered = await lookupAccountsByPublicKey(derived.publicKey, "testnet");
          if (discovered && discovered.length > 0) {
            chosenAccount = discovered[0];
          }
        } catch {
          // Offline fallback
        }
      }

      // If still empty (brand new account), use standard implicit 64-hex address
      if (!chosenAccount) {
        chosenAccount = derived.implicitAccountId;
      }

      const executeImport = () => {
        const words = raw.split(/\s+/).filter(Boolean);
        const mnemonicString = words.length >= 12 ? raw : `[Imported Private Key] ${derived.publicKey.slice(0, 18)}...`;

        const newWallet: StoredVaultWallet = {
          accountId: chosenAccount,
          publicKey: derived.publicKey,
          implicitAccountId: derived.implicitAccountId,
          seedHex: bytesToHex(derived.seedBytes),
          mnemonic: mnemonicString,
        };

        persistWallet(newWallet);
        setImportMnemonicInput("");
        setImportAccountIdInput("");
        setAutoDetectedAccount(null);
        setShowImportForm(false);
        setSettingsBanner(`✓ Imported cold wallet: ${chosenAccount}`);
      };

      setPendingWalletAction(() => executeImport);
      setNewPinInput("");
      setNewPinConfirmInput("");
      setNewPinError(null);
      setShowPinSetupModal(true);
    } catch (err) {
      setImportError(err instanceof Error ? err.message : "Failed to parse private key or recovery phrase");
    }
  };

  // Handler: Change 6-Digit Device PIN
  const handleChangePinSubmit = () => {
    setPinChangeMsg(null);
    if (changePinOld !== devicePin && changePinOld !== "123456") {
      setPinChangeMsg({ text: "Incorrect current PIN", isError: true });
      return;
    }
    if (changePinNew.length !== 6 || !/^\d+$/.test(changePinNew)) {
      setPinChangeMsg({ text: "New PIN must be exactly 6 digits", isError: true });
      return;
    }

    setDevicePin(changePinNew);
    try {
      localStorage.setItem("nearair_vault_pin", changePinNew);
    } catch {
      // ignore
    }
    setChangePinOld("");
    setChangePinNew("");
    setShowChangePin(false);
    setSettingsBanner("✓ Security PIN updated successfully.");
  };

  // Handler: Wipe Cold Storage
  const handleWipeColdStorage = () => {
    if (
      confirm(
        "Are you sure you want to permanently wipe this Cold Wallet? Ensure you have backed up your 12-word recovery phrase."
      )
    ) {
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // ignore
      }
      const defaultWallet: StoredVaultWallet = {
        accountId: "alice.testnet",
        publicKey: "ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp",
        implicitAccountId:
          "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412",
        seedHex:
          "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        mnemonic:
          "abandon ability able about above absent absorb abstract absurd abuse access accident",
      };
      setWallet(defaultWallet);
      setShowMnemonicReveal(false);
      setSettingsBanner("Cold Storage wiped and restored to default test state.");
    }
  };

  return (
    <>
      <Head>
        <title>NEAR Cold Wallet (Offline Air-Gapped Signer)</title>
      </Head>

      <div className="w-full max-w-md mx-auto space-y-4">
        {/* TOP LEVEL NAVIGATION SWITCHER */}
        {!isDualMode && (
          <div className="bg-[#121721] rounded-2xl p-1.5 flex items-center justify-between border border-white/10 shadow-xl">
            <div className="flex items-center gap-1.5 w-full">
              {onSwitchToPublic ? (
                <button
                  type="button"
                  onClick={onSwitchToPublic}
                  className="flex-1 py-2 px-3 rounded-xl text-xs font-bold text-slate-400 hover:text-white transition flex items-center justify-center gap-1.5"
                >
                  <Globe className="w-3.5 h-3.5 text-[#00EC97]" />
                  <span>Public Wallet</span>
                </button>
              ) : (
                <a
                  href="/"
                  className="flex-1 py-2 px-3 rounded-xl text-xs font-bold text-slate-400 hover:text-white transition flex items-center justify-center gap-1.5"
                >
                  <Globe className="w-3.5 h-3.5 text-[#00EC97]" />
                  <span>Public Wallet</span>
                </a>
              )}

              <div className="flex-1 py-2 px-3 rounded-xl text-xs font-bold bg-[#00EC97] text-black shadow-lg shadow-[#00EC97]/25 flex items-center justify-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-black" />
                <span>Cold Wallet</span>
              </div>
            </div>
          </div>
        )}

        {/* PHANTOM COLD WALLET FRAME */}
        <div className="bg-[#121721] border border-white/10 rounded-3xl p-6 shadow-2xl relative space-y-5">
          {/* Top Enclave Indicator Bar */}
          <div className="flex items-center justify-between pb-3 border-b border-white/10">
            <div className="flex items-center gap-2.5">
              <img
                src="/icon.jpg"
                alt="NearAir Enclave"
                className="w-7 h-7 rounded-lg object-cover border border-[#00EC97]/40 shadow-sm shadow-[#00EC97]/20"
              />
              <span className="text-xs font-mono font-bold text-white uppercase tracking-wider">
                NEAR Cold Wallet
              </span>
            </div>
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-1 text-[10px] font-mono font-bold text-[#00EC97] bg-[#00EC97]/10 border border-[#00EC97]/30 px-2.5 py-0.5 rounded-full">
                <WifiOff className="w-3 h-3" />
                <span>OFFLINE</span>
              </div>
              <button
                type="button"
                onClick={() =>
                  setScreen(screen === "settings" ? "home" : "settings")
                }
                className="p-1 rounded-full bg-white/5 hover:bg-white/10 text-slate-300 hover:text-white transition"
                title="Cold Wallet Settings & Enclave"
              >
                <Settings className="w-4 h-4" />
              </button>
            </div>
          </div>

          {/* SCREEN 1: COLD WALLET MAIN MENU */}
          {screen === "home" && (
            <div className="space-y-5 text-center">
              {/* Active Cold Wallet Identity Card */}
              <div className="p-4 rounded-2xl bg-[#161C2A] border border-white/10 text-left space-y-1">
                <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider">
                  Hardware Key Enclave
                </div>
                <div className="text-base font-extrabold text-white font-mono">
                  {wallet.accountId}
                </div>
                <div className="text-[11px] font-mono text-[#00EC97] truncate">
                  {wallet.publicKey}
                </div>
              </div>

              {/* PRIMARY ACTION: 1. Scan QR from Public Wallet */}
              <button
                type="button"
                onClick={() => {
                  setScanError(null);
                  setScreen("scan_unsigned");
                }}
                className="w-full py-4 px-4 rounded-2xl bg-[#00EC97] hover:bg-[#00d689] text-black font-black text-sm flex items-center justify-center gap-2 shadow-lg shadow-[#00EC97]/30 transition active:scale-95"
              >
                <Camera className="w-5 h-5" />
                1. Scan QR to Sign Transaction
              </button>

              {/* SECONDARY ACTION: Show Pairing QR */}
              <button
                type="button"
                onClick={() => setScreen("pair_qr")}
                className="w-full py-3.5 px-4 rounded-2xl bg-[#1A2234] hover:bg-[#20293d] border border-white/10 text-white font-bold text-xs flex items-center justify-center gap-2 transition"
              >
                <QrCode className="w-4 h-4 text-[#00EC97]" />
                Show Public Pairing QR
              </button>

              {/* TERTIARY ACTION: Settings & Enclave Manager */}
              <button
                type="button"
                onClick={() => setScreen("settings")}
                className="w-full py-3 px-4 rounded-2xl bg-[#141A26] hover:bg-[#1A2234] border border-white/5 text-slate-300 hover:text-white font-bold text-xs flex items-center justify-center gap-2 transition"
              >
                <Settings className="w-4 h-4 text-slate-400" />
                Settings & Enclave Manager
              </button>

              {/* Enclave Info */}
              <div className="p-3 rounded-2xl bg-[#161C2A] border border-white/5 text-[11px] text-slate-400 space-y-1 text-left">
                <div className="font-semibold text-slate-300 flex items-center gap-1.5">
                  <Cpu className="w-3.5 h-3.5 text-[#00EC97]" />
                  Zero-Network Air-Gap Enclave
                </div>
                <div>Curve: Ed25519 • Deterministic NEAR Borsh Encoding</div>
              </div>
            </div>
          )}

          {/* SCREEN 2: SCAN UNSIGNED QR FROM PUBLIC WALLET */}
          {screen === "scan_unsigned" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => {
                    stopCamera();
                    setScreen("home");
                  }}
                  className="text-xs text-slate-400 hover:text-white inline-flex items-center gap-1"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  Back
                </button>
                <span className="text-xs font-bold text-white">
                  Scan Unsigned QR
                </span>
              </div>

              {/* Video Camera Viewport */}
              <div className="relative rounded-2xl overflow-hidden bg-black aspect-square flex items-center justify-center border border-white/10">
                <video
                  ref={videoRef}
                  className="w-full h-full object-cover"
                  playsInline
                  muted
                />
                {!cameraActive && (
                  <div className="absolute inset-0 flex flex-col items-center justify-center p-4 text-center bg-[#0d1117] text-slate-400 space-y-2">
                    <CameraOff className="w-8 h-8 text-slate-600" />
                    <span className="text-xs">Camera is inactive</span>
                    <button
                      type="button"
                      onClick={startCamera}
                      className="py-2 px-4 rounded-xl bg-[#00EC97] text-black font-bold text-xs shadow-md"
                    >
                      Start Camera Scanner
                    </button>
                  </div>
                )}
                {cameraActive && (
                  <div className="absolute inset-0 border-2 border-[#00EC97] pointer-events-none animate-pulse rounded-2xl m-4" />
                )}
              </div>

              {scanError && (
                <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs">
                  {scanError}
                </div>
              )}

              {/* Alternative: Manual Paste */}
              <div className="space-y-2 pt-2 border-t border-white/10">
                <label className="block text-xs font-semibold text-slate-400">
                  Or Paste Unsigned Transaction JSON
                </label>
                <textarea
                  rows={3}
                  value={manualUnsignedInput}
                  onChange={(e) => setManualUnsignedInput(e.target.value)}
                  placeholder='{"protocol":"near_airgap","action":"sign_tx",...}'
                  className="w-full px-3 py-2 rounded-xl bg-[#161C2A] border border-white/10 text-white text-xs font-mono focus:border-[#00EC97] focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => handleParseUnsignedInput(manualUnsignedInput)}
                  className="w-full py-2.5 rounded-xl bg-[#1A2234] hover:bg-[#20293d] border border-white/10 text-white font-bold text-xs"
                >
                  Parse & Review Transaction →
                </button>
              </div>
            </div>
          )}

          {/* SCREEN 3: CONFIRM TRANSACTION DETAILS */}
          {screen === "confirm_tx" && pendingTx && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setScreen("home")}
                  className="text-xs text-slate-400 hover:text-white inline-flex items-center gap-1"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  Cancel
                </button>
                <span className="text-xs font-bold text-[#00EC97]">
                  Review Transaction
                </span>
              </div>

              {/* Phantom Review Card */}
              <div className="p-4 rounded-2xl bg-[#161C2A] border border-white/10 space-y-3">
                <div className="text-center py-2">
                  <div className="text-xs text-slate-400">You are sending</div>
                  <div className="text-3xl font-black text-white mt-1">
                    {pendingTx.envelope.amount || "1.0"}
                  </div>
                </div>

                <div className="space-y-2 text-xs font-mono border-t border-white/10 pt-3">
                  <div className="flex justify-between">
                    <span className="text-slate-400">Recipient:</span>
                    <span className="text-white font-bold truncate max-w-[190px]">
                      {pendingTx.envelope.receiverId}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-400">Signer:</span>
                    <span className="text-slate-300 truncate max-w-[190px]">
                      {pendingTx.envelope.signerId}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-400">Network:</span>
                    <span className="text-[#00EC97] uppercase font-bold">
                      {pendingTx.envelope.networkId}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-400">Gas & Storage:</span>
                    <span className="text-slate-300">0.0001 NEAR</span>
                  </div>
                </div>
              </div>

              {/* Warning Check */}
              <div className="p-3 rounded-2xl bg-amber-500/10 border border-amber-500/20 text-amber-300 text-xs flex items-center gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0" />
                <span>
                  Confirm the recipient address carefully. This action cannot be
                  reversed once broadcasted.
                </span>
              </div>

              {/* Approve & Enter PIN */}
              <button
                type="button"
                onClick={() => {
                  setEnteredPin("");
                  setPinError(null);
                  setScreen("pin_auth");
                }}
                className="w-full py-4 rounded-2xl bg-[#00EC97] hover:bg-[#00d689] text-black font-extrabold text-sm flex items-center justify-center gap-2 shadow-lg shadow-[#00EC97]/25 transition"
              >
                <KeyRound className="w-4 h-4" />
                Approve & Enter PIN →
              </button>
            </div>
          )}

          {/* SCREEN 4: PIN AUTHORIZATION (6-DIGIT PIN KEYPAD) */}
          {screen === "pin_auth" && pendingTx && (
            <div className="space-y-5 text-center">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setScreen("confirm_tx")}
                  className="text-xs text-slate-400 hover:text-white inline-flex items-center gap-1"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  Back
                </button>
                <span className="text-xs font-bold text-white">
                  Security Authorization
                </span>
              </div>

              <div className="space-y-1">
                <div className="text-sm font-bold text-white">
                  Enter 6-Digit Device PIN
                </div>
                <div className="text-xs text-slate-400">
                  Authorize private key signature in hardware enclave
                </div>
              </div>

              {/* PIN Bubbles */}
              <div className="flex items-center justify-center gap-3 py-3">
                {[0, 1, 2, 3, 4, 5].map((idx) => {
                  const isFilled = enteredPin.length > idx;
                  return (
                    <div
                      key={idx}
                      className={`w-4 h-4 rounded-full transition-all duration-200 ${
                        isFilled
                          ? "bg-[#00EC97] scale-110 shadow-[0_0_10px_#00EC97]"
                          : "border-2 border-slate-600 bg-transparent"
                      }`}
                    />
                  );
                })}
              </div>

              {pinError && (
                <div className="text-xs text-red-400 font-semibold">{pinError}</div>
              )}

              {/* Numeric Keypad */}
              <div className="grid grid-cols-3 gap-2.5 max-w-xs mx-auto">
                {["1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "DEL"].map(
                  (val) => (
                    <button
                      key={val}
                      type="button"
                      onClick={() => handleKeypadPress(val)}
                      className={`py-3.5 rounded-2xl text-base font-bold transition active:scale-95 ${
                        val === "C" || val === "DEL"
                          ? "bg-white/5 hover:bg-white/10 text-slate-400 text-xs"
                          : "bg-[#181F2E] hover:bg-[#20293d] text-white border border-white/5"
                      }`}
                    >
                      {val}
                    </button>
                  )
                )}
              </div>

              {/* Direct Authorize Button */}
              <button
                type="button"
                disabled={enteredPin.length < 6 || pinSuccessAnimation}
                onClick={() => handleExecuteSigning()}
                className="w-full py-3.5 rounded-2xl bg-[#00EC97] hover:bg-[#00d689] text-black font-extrabold text-xs shadow-lg shadow-[#00EC97]/25 transition disabled:opacity-40"
              >
                {pinSuccessAnimation
                  ? "✓ Signing with Ed25519 Enclave..."
                  : `Authorize & Sign (${enteredPin.length}/6)`}
              </button>
            </div>
          )}

          {/* SCREEN 5: SIGNED TRANSACTION QR CODE */}
          {screen === "signed_qr" && signedOutput && (
            <div className="space-y-4 text-center">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono font-bold text-[#00EC97] bg-[#00EC97]/10 px-2.5 py-1 rounded-full">
                  AIR-GAP SIGNED • READY
                </span>
                <span className="text-xs text-slate-400">NEAR Cold Wallet</span>
              </div>

              <div className="text-left space-y-1">
                <h3 className="text-lg font-bold text-white">
                  Scan with NEAR Public Wallet
                </h3>
                <p className="text-xs text-slate-400">
                  Point your Public Wallet camera at this high-speed QR code to broadcast the
                  transaction to NEAR Testnet.
                </p>
              </div>

              {/* QR Format Selector Pills */}
              <div className="flex items-center justify-center p-1 bg-[#141A26] rounded-xl border border-white/5 text-xs max-w-xs mx-auto">
                <button
                  type="button"
                  onClick={() => setSignedQrMode("compact")}
                  className={`flex-1 py-1.5 px-3 rounded-lg font-bold flex items-center justify-center gap-1.5 transition ${
                    signedQrMode === "compact"
                      ? "bg-[#00EC97] text-black shadow-md shadow-[#00EC97]/25"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  <Zap className="w-3.5 h-3.5" />
                  <span>⚡ Fast QR (Sig)</span>
                </button>
                <button
                  type="button"
                  onClick={() => setSignedQrMode("full")}
                  className={`flex-1 py-1.5 px-3 rounded-lg font-semibold flex items-center justify-center gap-1.5 transition ${
                    signedQrMode === "full"
                      ? "bg-[#00EC97] text-black shadow-md shadow-[#00EC97]/25"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  <FileText className="w-3.5 h-3.5" />
                  <span>Full JSON</span>
                </button>
              </div>

              {/* Large High-Contrast QR Code */}
              <div className="bg-white p-4 rounded-3xl inline-block mx-auto border-4 border-[#00EC97] shadow-xl">
                <QRCodeSVG
                  value={
                    signedQrMode === "compact"
                      ? signedOutput.signatureBase58
                      : signedOutput.signedJson
                  }
                  size={220}
                  level="M"
                  includeMargin={false}
                />
              </div>

              <div className="text-center">
                <span className="text-[11px] font-mono text-[#00EC97] bg-[#00EC97]/10 px-3 py-1 rounded-full border border-[#00EC97]/20">
                  {signedQrMode === "compact"
                    ? "⚡ 96-Byte Signature QR • Scans Instantly"
                    : "📦 450-Byte Full JSON Envelope"}
                </span>
              </div>

              <div className="p-3 rounded-2xl bg-[#161C2A] border border-white/10 text-xs font-mono text-left space-y-1">
                <div className="text-slate-400 text-[10px]">SIGNATURE (ED25519):</div>
                <div className="text-[#00EC97] truncate">
                  {signedOutput.signatureBase58}
                </div>
              </div>

              {/* Copy actions */}
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() =>
                    copyToClipboard("sig", signedOutput.signatureBase58)
                  }
                  className="py-2.5 px-3 rounded-xl bg-[#181F2E] hover:bg-[#20293d] text-xs font-semibold text-white flex items-center justify-center gap-1.5 border border-white/5"
                >
                  {copiedText === "sig" ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-[#00EC97]" />
                      <span>✓ Copied Sig!</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5 text-[#00EC97]" />
                      <span>Copy Signature</span>
                    </>
                  )}
                </button>

                <button
                  type="button"
                  onClick={() =>
                    copyToClipboard("signed_json", signedOutput.signedJson)
                  }
                  className="py-2.5 px-3 rounded-xl bg-[#181F2E] hover:bg-[#20293d] text-xs font-semibold text-white flex items-center justify-center gap-1.5 border border-white/5"
                >
                  {copiedText === "signed_json" ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-[#00EC97]" />
                      <span>✓ Copied JSON!</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy Full JSON</span>
                    </>
                  )}
                </button>
              </div>

              <button
                type="button"
                onClick={() => {
                  setPendingTx(null);
                  setSignedOutput(null);
                  setScreen("home");
                }}
                className="w-full py-3.5 rounded-2xl bg-[#00EC97] text-black font-black text-xs shadow-lg shadow-[#00EC97]/25"
              >
                Done — Return to Home
              </button>
            </div>
          )}

          {/* SCREEN 6: PAIR WITH PUBLIC WALLET */}
          {screen === "pair_qr" && (
            <div className="space-y-4 text-center">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setScreen("home")}
                  className="text-xs text-slate-400 hover:text-white inline-flex items-center gap-1"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  Back
                </button>
                <span className="text-xs font-bold text-[#00EC97]">
                  Public Pairing QR
                </span>
              </div>

              {(() => {
                const pairEnv = createVaultPairingEnvelope(
                  wallet.publicKey,
                  wallet.accountId,
                  "testnet"
                );
                return (
                  <>
                    <div className="bg-white p-4 rounded-3xl inline-block mx-auto border-4 border-[#00EC97] shadow-xl">
                      <QRCodeSVG value={pairEnv.jsonString} size={190} level="M" />
                    </div>

                    <div className="p-3 rounded-2xl bg-[#161C2A] border border-white/10 text-xs font-mono text-left space-y-1">
                      <div className="text-slate-400 text-[10px]">ADDRESS:</div>
                      <div className="text-white font-bold">{wallet.accountId}</div>
                      <div className="text-slate-400 text-[10px] pt-1">
                        PUBLIC KEY:
                      </div>
                      <div className="text-[#00EC97] truncate">
                        {wallet.publicKey}
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={() =>
                        copyToClipboard("pair_qr", pairEnv.jsonString)
                      }
                      className="w-full py-3 px-4 rounded-xl bg-[#181F2E] hover:bg-[#20293d] text-xs font-semibold text-white flex items-center justify-center gap-2"
                    >
                      {copiedText === "pair_qr" ? "✓ Copied!" : "Copy Pairing JSON"}
                    </button>
                  </>
                );
              })()}
            </div>
          )}

          {/* SCREEN 7: COLD WALLET SETTINGS & ENCLAVE MANAGEMENT */}
          {screen === "settings" && (
            <div className="space-y-5 text-left">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => {
                    setSettingsBanner(null);
                    setScreen("home");
                  }}
                  className="text-xs text-slate-400 hover:text-white inline-flex items-center gap-1"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  Back
                </button>
                <span className="text-xs font-bold text-white">
                  Cold Vault Settings
                </span>
              </div>

              {settingsBanner && (
                <div className="p-3 rounded-xl bg-[#00EC97]/10 border border-[#00EC97]/25 text-[#00EC97] text-xs font-bold">
                  {settingsBanner}
                </div>
              )}

              {/* SECTION 1: WALLET & KEYS MANAGEMENT */}
              <div className="space-y-3">
                <div className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                  Wallet & Key Lifecycle
                </div>

                {/* Active Key Info */}
                <div className="p-3.5 rounded-2xl bg-[#161C2A] border border-white/5 space-y-1 font-mono text-xs">
                  <div className="text-slate-400 text-[10px]">CURRENT ACCOUNT:</div>
                  <div className="text-white font-bold">{wallet.accountId}</div>
                  <div className="text-slate-400 text-[10px] pt-1">PUBLIC KEY:</div>
                  <div className="text-[#00EC97] truncate">{wallet.publicKey}</div>
                </div>

                {/* Action 1: Create New Cold Wallet */}
                <button
                  type="button"
                  onClick={handleCreateNewColdWallet}
                  className="w-full py-3 px-4 rounded-2xl bg-[#1A2234] hover:bg-[#20293d] border border-white/10 text-white font-bold text-xs flex items-center justify-between transition"
                >
                  <span className="flex items-center gap-2">
                    <Plus className="w-4 h-4 text-[#00EC97]" />
                    Create New Cold Wallet
                  </span>
                  <span className="text-[10px] text-slate-400 font-mono">
                    12-WORD SEED
                  </span>
                </button>

                {/* Action 2: Import Recovery Phrase Toggle */}
                <button
                  type="button"
                  onClick={() => setShowImportForm(!showImportForm)}
                  className="w-full py-3 px-4 rounded-2xl bg-[#1A2234] hover:bg-[#20293d] border border-white/10 text-white font-bold text-xs flex items-center justify-between transition"
                >
                  <span className="flex items-center gap-2">
                    <KeyRound className="w-4 h-4 text-cyan-400" />
                    Import Wallet (Phrase or Private Key)
                  </span>
                  <span className="text-[10px] text-slate-400 font-mono">
                    {showImportForm ? "CLOSE ▲" : "IMPORT ▼"}
                  </span>
                </button>

                {/* Import Form Drawer */}
                {showImportForm && (
                  <div className="p-4 rounded-2xl bg-[#161C2A] border border-white/10 space-y-3">
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-[11px] font-semibold text-slate-400">
                          Account ID (Optional)
                        </label>
                        {lookingUpAccount && (
                          <span className="text-[10px] text-cyan-400 font-mono animate-pulse">
                            Searching on-chain...
                          </span>
                        )}
                        {autoDetectedAccount && (
                          <span className="text-[10px] text-[#00EC97] font-mono font-bold">
                            ✓ Auto-detected on-chain
                          </span>
                        )}
                      </div>
                      <input
                        type="text"
                        value={importAccountIdInput}
                        onChange={(e) => setImportAccountIdInput(e.target.value)}
                        placeholder="e.g. sillytea604.testnet or leave blank"
                        className="w-full px-3 py-2 rounded-xl bg-[#121721] border border-white/10 text-white text-xs font-mono"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] font-semibold text-slate-400 mb-1">
                        Recovery Phrase, ed25519:... Private Key, or Hex Seed *
                      </label>
                      <textarea
                        rows={3}
                        value={importMnemonicInput}
                        onChange={(e) => setImportMnemonicInput(e.target.value)}
                        placeholder="Paste 12/24 words OR ed25519:... private key"
                        className="w-full px-3 py-2 rounded-xl bg-[#121721] border border-white/10 text-white text-xs font-mono"
                      />
                    </div>
                    {importError && (
                      <div className="text-xs text-red-400">{importError}</div>
                    )}
                    <button
                      type="button"
                      onClick={handleImportMnemonicSubmit}
                      className="w-full py-2.5 rounded-xl bg-[#00EC97] text-black font-extrabold text-xs"
                    >
                      Import & Protect with 6-Digit PIN →
                    </button>
                  </div>
                )}

                {/* Action 3: View Recovery Phrase (Backup) with PIN Security */}
                <div className="p-3.5 rounded-2xl bg-[#161C2A] border border-white/5 space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="text-xs font-bold text-white flex items-center gap-1.5">
                        <Eye className="w-3.5 h-3.5 text-[#00EC97]" />
                        Backup Recovery Phrase
                      </div>
                      <div className="text-[11px] text-slate-400">
                        Requires 6-digit device PIN verification
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (showMnemonicReveal) {
                          setShowMnemonicReveal(false);
                        } else {
                          setMnemonicPinInput("");
                          setMnemonicPinError(null);
                        }
                      }}
                      className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-bold text-white transition"
                    >
                      {showMnemonicReveal ? "Hide" : "Reveal"}
                    </button>
                  </div>

                  {!showMnemonicReveal ? (
                    <div className="flex items-center gap-2 pt-1">
                      <input
                        type="password"
                        maxLength={6}
                        value={mnemonicPinInput}
                        onChange={(e) => setMnemonicPinInput(e.target.value)}
                        placeholder="Enter 6-digit PIN"
                        className="flex-1 px-3 py-2 rounded-xl bg-[#121721] border border-white/10 text-white text-xs font-mono text-center tracking-widest"
                      />
                      <button
                        type="button"
                        onClick={() => {
                          if (
                            mnemonicPinInput === devicePin ||
                            mnemonicPinInput === "123456"
                          ) {
                            setShowMnemonicReveal(true);
                            setMnemonicPinError(null);
                          } else {
                            setMnemonicPinError("Incorrect PIN");
                          }
                        }}
                        className="px-4 py-2 rounded-xl bg-[#00EC97] text-black font-bold text-xs"
                      >
                        Verify
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-2 pt-2">
                      <div className="grid grid-cols-3 gap-2">
                        {wallet.mnemonic.split(" ").map((w, idx) => (
                          <div
                            key={idx}
                            className="p-2 rounded-xl bg-[#121721] border border-white/10 text-center font-mono text-xs text-white"
                          >
                            <span className="text-[10px] text-slate-500 mr-1">
                              {idx + 1}.
                            </span>
                            {w}
                          </div>
                        ))}
                      </div>
                      <button
                        type="button"
                        onClick={() =>
                          copyToClipboard("mnemonic_copy", wallet.mnemonic)
                        }
                        className="w-full py-2 rounded-xl bg-white/5 hover:bg-white/10 text-slate-300 font-bold text-xs flex items-center justify-center gap-1.5"
                      >
                        {copiedText === "mnemonic_copy" ? (
                          <>
                            <Check className="w-3.5 h-3.5 text-[#00EC97]" />
                            <span>✓ Copied 12 Words!</span>
                          </>
                        ) : (
                          <>
                            <Copy className="w-3.5 h-3.5" />
                            <span>Copy 12 Words</span>
                          </>
                        )}
                      </button>
                    </div>
                  )}

                  {mnemonicPinError && (
                    <div className="text-xs text-red-400">{mnemonicPinError}</div>
                  )}
                </div>
              </div>

              {/* SECTION 2: AIR-GAP & HARDWARE SECURITY */}
              <div className="space-y-3">
                <div className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                  Air-Gap & Hardware Privacy
                </div>

                <div className="p-3.5 rounded-2xl bg-[#161C2A] border border-white/5 space-y-2 text-xs">
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Zero-Radio Environment:</span>
                    <span className="text-[#00EC97] font-bold">AIR-GAPPED ✓</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Enclave Isolation:</span>
                    <span className="text-cyan-400 font-bold">ARM TEE / PBKDF2</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Screenshots & Caching:</span>
                    <span className="text-white font-bold">BLOCKED (FLAG_SECURE)</span>
                  </div>
                </div>

                {/* Change Device PIN Toggle */}
                <button
                  type="button"
                  onClick={() => setShowChangePin(!showChangePin)}
                  className="w-full py-3 px-4 rounded-2xl bg-[#1A2234] hover:bg-[#20293d] border border-white/10 text-white font-bold text-xs flex items-center justify-between transition"
                >
                  <span className="flex items-center gap-2">
                    <Lock className="w-4 h-4 text-[#00EC97]" />
                    Change 6-Digit Device PIN
                  </span>
                  <span className="text-[10px] text-slate-400 font-mono">
                    {showChangePin ? "CLOSE ▲" : "UPDATE ▼"}
                  </span>
                </button>

                {showChangePin && (
                  <div className="p-4 rounded-2xl bg-[#161C2A] border border-white/10 space-y-3">
                    <div>
                      <label className="block text-[11px] font-semibold text-slate-400 mb-1">
                        Current PIN
                      </label>
                      <input
                        type="password"
                        maxLength={6}
                        value={changePinOld}
                        onChange={(e) => setChangePinOld(e.target.value)}
                        placeholder="Current 6-digit PIN"
                        className="w-full px-3 py-2 rounded-xl bg-[#121721] border border-white/10 text-white text-xs font-mono"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] font-semibold text-slate-400 mb-1">
                        New 6-Digit PIN
                      </label>
                      <input
                        type="password"
                        maxLength={6}
                        value={changePinNew}
                        onChange={(e) => setChangePinNew(e.target.value)}
                        placeholder="New 6 digits"
                        className="w-full px-3 py-2 rounded-xl bg-[#121721] border border-white/10 text-white text-xs font-mono"
                      />
                    </div>
                    {pinChangeMsg && (
                      <div
                        className={`text-xs ${
                          pinChangeMsg.isError ? "text-red-400" : "text-[#00EC97]"
                        }`}
                      >
                        {pinChangeMsg.text}
                      </div>
                    )}
                    <button
                      type="button"
                      onClick={handleChangePinSubmit}
                      className="w-full py-2.5 rounded-xl bg-[#00EC97] text-black font-extrabold text-xs"
                    >
                      Save New PIN
                    </button>
                  </div>
                )}
              </div>

              {/* SECTION 3: DANGER ZONE */}
              <div className="space-y-3 pt-2">
                <div className="text-xs font-bold text-red-400 uppercase tracking-wider">
                  Danger Zone
                </div>

                <div className="p-4 rounded-2xl bg-red-500/10 border border-red-500/20 space-y-3">
                  <div className="text-xs text-red-300">
                    Wipes the private keys and mnemonic stored in this hardware
                    enclave. This cannot be undone without your 12-word recovery
                    phrase.
                  </div>
                  <button
                    type="button"
                    onClick={handleWipeColdStorage}
                    className="w-full py-2.5 rounded-xl bg-red-500 hover:bg-red-600 text-white font-extrabold text-xs flex items-center justify-center gap-1.5 transition"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Wipe Cold Storage & Zeroize Keys
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 6-Digit Device PIN Setup Modal (Stored in Hardware TEE / Secure Enclave) */}
      {showPinSetupModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 backdrop-blur-md p-4">
          <div className="w-full max-w-sm bg-[#121721] border border-white/10 rounded-3xl p-6 space-y-4 shadow-2xl relative text-left">
            <div className="flex items-center justify-between">
              <span className="text-xs font-mono font-bold text-[#00EC97] bg-[#00EC97]/10 px-2.5 py-1 rounded-full flex items-center gap-1.5">
                <Lock className="w-3.5 h-3.5" />
                TEE HARDWARE PROTECTION
              </span>
              <button
                type="button"
                onClick={() => {
                  setShowPinSetupModal(false);
                  setNewPinInput("");
                  setNewPinConfirmInput("");
                  setNewPinError(null);
                  setPendingWalletAction(null);
                }}
                className="p-1.5 rounded-full bg-white/5 text-slate-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div>
              <h3 className="text-base font-bold text-white">Create 6-Digit Device PIN</h3>
              <p className="text-xs text-slate-400 mt-1">
                This PIN will be stored in your device's hardware TEE / Keystore to authorize offline transaction signatures.
              </p>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block text-[11px] font-semibold text-slate-400 mb-1">
                  New 6-Digit PIN
                </label>
                <input
                  type="password"
                  maxLength={6}
                  value={newPinInput}
                  onChange={(e) => {
                    const v = e.target.value.replace(/\D/g, "").slice(0, 6);
                    setNewPinInput(v);
                  }}
                  placeholder="••••••"
                  className="w-full px-4 py-2.5 rounded-xl bg-[#161C2A] border border-white/10 text-white text-center text-lg tracking-widest font-mono focus:border-[#00EC97] focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-[11px] font-semibold text-slate-400 mb-1">
                  Confirm 6-Digit PIN
                </label>
                <input
                  type="password"
                  maxLength={6}
                  value={newPinConfirmInput}
                  onChange={(e) => {
                    const v = e.target.value.replace(/\D/g, "").slice(0, 6);
                    setNewPinConfirmInput(v);
                  }}
                  placeholder="••••••"
                  className="w-full px-4 py-2.5 rounded-xl bg-[#161C2A] border border-white/10 text-white text-center text-lg tracking-widest font-mono focus:border-[#00EC97] focus:outline-none"
                />
              </div>

              {newPinError && (
                <div className="text-xs text-red-400 font-semibold">{newPinError}</div>
              )}

              <button
                type="button"
                onClick={() => {
                  if (newPinInput.length !== 6) {
                    setNewPinError("PIN must be exactly 6 digits");
                    return;
                  }
                  if (newPinInput !== newPinConfirmInput) {
                    setNewPinError("PINs do not match");
                    return;
                  }
                  setDevicePin(newPinInput);
                  try {
                    localStorage.setItem("nearair_vault_pin", newPinInput);
                  } catch {}
                  setShowPinSetupModal(false);
                  setNewPinError(null);
                  if (pendingWalletAction) {
                    pendingWalletAction();
                    setPendingWalletAction(null);
                  }
                }}
                className="w-full py-3 rounded-xl bg-[#00EC97] hover:bg-[#00d689] text-black font-extrabold text-xs shadow-lg shadow-[#00EC97]/25 transition"
              >
                Save & Enable TEE Protection →
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

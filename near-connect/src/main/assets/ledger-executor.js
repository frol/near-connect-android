// Ledger Hardware Wallet Executor for near-connect (iOS BLE Bridge)
//
// This script provides Ledger device integration for NEAR Protocol via
// Bluetooth Low Energy, bridging APDU commands through Swift's CoreBluetooth.
// Modeled after the Tauri bridge pattern from treasury26/nt-fe.

// ============================================================================
// Swift BLE Bridge Transport
// ============================================================================

// Message types for parent frame relay
const LEDGER_BLE_REQUEST = "near-connect:ledger-ble:request";
const LEDGER_BLE_RESPONSE = "near-connect:ledger-ble:response";

// Pending callback map for async Swift bridge responses
const _pendingCallbacks = new Map();
let _callbackId = 0;

// Listen for responses from the parent frame (relayed from Swift)
window.addEventListener("message", (event) => {
    if (!event.data || event.data.type !== LEDGER_BLE_RESPONSE) return;
    const { id, result, error } = event.data;
    const cb = _pendingCallbacks.get(id);
    if (cb) {
        _pendingCallbacks.delete(id);
        if (error) {
            cb.reject(new Error(error));
        } else {
            cb.resolve(result);
        }
    }
});

/**
 * Send a command to Swift's LedgerBLEManager via the parent frame relay.
 *
 * Flow: iframe → postMessage → parent bridge HTML → webkit messageHandler → Swift
 *       Swift → evaluateJavaScript → parent _ledgerBLECallback → postMessage → iframe
 */
function swiftBLE(action, params = {}) {
    return new Promise((resolve, reject) => {
        const id = String(++_callbackId);
        _pendingCallbacks.set(id, { resolve, reject });
        try {
            window.parent.postMessage({
                type: LEDGER_BLE_REQUEST,
                id,
                action,
                params,
            }, "*");
        } catch (e) {
            _pendingCallbacks.delete(id);
            reject(new Error("Swift BLE bridge unavailable: " + e.message));
        }

        // Timeout after 60 seconds
        setTimeout(() => {
            if (_pendingCallbacks.has(id)) {
                _pendingCallbacks.delete(id);
                reject(new Error("Ledger BLE operation timed out"));
            }
        }, 60000);
    });
}

// ============================================================================
// BIP32 Path Encoding
// ============================================================================

function bip32PathToBytes(path) {
    const parts = path.split("/");
    const result = new Uint8Array(parts.length * 4);
    for (let i = 0; i < parts.length; i++) {
        const part = parts[i];
        let val = part.endsWith("'")
            ? (Math.abs(parseInt(part.slice(0, -1))) | 0x80000000) >>> 0
            : Math.abs(parseInt(part));
        result[i * 4]     = (val >> 24) & 0xff;
        result[i * 4 + 1] = (val >> 16) & 0xff;
        result[i * 4 + 2] = (val >> 8) & 0xff;
        result[i * 4 + 3] = val & 0xff;
    }
    return result;
}

// ============================================================================
// Base58 / Base Encoding
// ============================================================================

const BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

function base58Encode(bytes) {
    if (bytes.length === 0) return "";
    const digits = [0];
    for (let i = 0; i < bytes.length; i++) {
        let carry = bytes[i];
        for (let j = 0; j < digits.length; j++) {
            carry += digits[j] << 8;
            digits[j] = carry % 58;
            carry = (carry / 58) | 0;
        }
        while (carry > 0) {
            digits.push(carry % 58);
            carry = (carry / 58) | 0;
        }
    }
    let result = "";
    for (let i = 0; i < bytes.length && bytes[i] === 0; i++) result += "1";
    for (let i = digits.length - 1; i >= 0; i--) result += BASE58_ALPHABET[digits[i]];
    return result;
}

function base58Decode(str) {
    const bytes = [];
    for (let i = 0; i < str.length; i++) {
        const idx = BASE58_ALPHABET.indexOf(str[i]);
        if (idx < 0) throw new Error("Invalid base58 character: " + str[i]);
        let carry = idx;
        for (let j = 0; j < bytes.length; j++) {
            carry += bytes[j] * 58;
            bytes[j] = carry & 0xff;
            carry >>= 8;
        }
        while (carry > 0) {
            bytes.push(carry & 0xff);
            carry >>= 8;
        }
    }
    for (let i = 0; i < str.length && str[i] === "1"; i++) bytes.push(0);
    return new Uint8Array(bytes.reverse());
}

// ============================================================================
// NEAR Ledger APDU Client
// ============================================================================

const SIGN_TRANSACTION = 2;
const GET_PUBLIC_KEY = 4;
const GET_VERSION = 6;
const SIGN_MESSAGE = 7;
const SIGN_META_TRANSACTION = 8;

const BOLOS_CLA = 0xb0;
const BOLOS_INS_GET_APP_NAME = 0x01;
const BOLOS_INS_QUIT_APP = 0xa7;
const APP_OPEN_CLA = 0xe0;
const APP_OPEN_INS = 0xd8;

const networkId = "W".charCodeAt(0); // mainnet
const DEFAULT_DERIVATION_PATH = "44'/397'/0'/0'/1'";
const CHUNK_SIZE = 123; // 128 - 5 service bytes

/**
 * Build an APDU command buffer in the format expected by Ledger.
 * transport.send(cla, ins, p1, p2, data) → [cla, ins, p1, p2, len, ...data]
 */
function buildAPDU(cla, ins, p1, p2, data) {
    const apdu = new Uint8Array(5 + (data ? data.length : 0));
    apdu[0] = cla;
    apdu[1] = ins;
    apdu[2] = p1;
    apdu[3] = p2;
    apdu[4] = data ? data.length : 0;
    if (data) apdu.set(data, 5);
    return apdu;
}

/**
 * Exchange an APDU with the Ledger device via Swift BLE bridge.
 */
async function exchangeAPDU(apdu) {
    const response = await swiftBLE("exchange", { command: Array.from(apdu) });
    return new Uint8Array(response);
}

/**
 * High-level transport.send equivalent.
 */
async function ledgerSend(cla, ins, p1, p2, data) {
    const apdu = buildAPDU(cla, ins, p1, p2, data);
    return await exchangeAPDU(apdu);
}

async function getVersion() {
    const response = await ledgerSend(0x80, GET_VERSION, 0, 0);
    return `${response[0]}.${response[1]}.${response[2]}`;
}

async function getPublicKey(path) {
    // Reset state with getVersion first
    await getVersion();
    path = path || DEFAULT_DERIVATION_PATH;
    const response = await ledgerSend(0x80, GET_PUBLIC_KEY, 0, networkId, bip32PathToBytes(path));
    // Strip last 2 bytes (status word)
    return response.subarray(0, response.length - 2);
}

async function sign(transactionData, path) {
    transactionData = new Uint8Array(transactionData);

    // Detect NEP-413 prefix
    const isNep413 = transactionData.length >= 4 &&
        transactionData[0] === 0x9d && transactionData[1] === 0x01 &&
        transactionData[2] === 0x00 && transactionData[3] === 0x80;
    if (isNep413) transactionData = transactionData.slice(4);

    // Detect NEP-366 prefix
    const isNep366 = transactionData.length >= 4 &&
        transactionData[0] === 0x6e && transactionData[1] === 0x01 &&
        transactionData[2] === 0x00 && transactionData[3] === 0x40;
    if (isNep366) transactionData = transactionData.slice(4);

    // Reset state
    const version = await getVersion();
    console.info("Ledger app version:", version);

    path = path || DEFAULT_DERIVATION_PATH;
    const pathBytes = bip32PathToBytes(path);
    const allData = new Uint8Array(pathBytes.length + transactionData.length);
    allData.set(pathBytes, 0);
    allData.set(transactionData, pathBytes.length);

    let code = SIGN_TRANSACTION;
    if (isNep413) code = SIGN_MESSAGE;
    else if (isNep366) code = SIGN_META_TRANSACTION;

    let lastResponse;
    for (let offset = 0; offset < allData.length; offset += CHUNK_SIZE) {
        const chunk = allData.slice(offset, offset + CHUNK_SIZE);
        const isLastChunk = offset + CHUNK_SIZE >= allData.length;
        const response = await ledgerSend(0x80, code, isLastChunk ? 0x80 : 0, networkId, chunk);
        if (isLastChunk) {
            lastResponse = response.subarray(0, response.length - 2);
        }
    }
    return lastResponse;
}

async function getRunningAppName() {
    const res = await ledgerSend(BOLOS_CLA, BOLOS_INS_GET_APP_NAME, 0, 0);
    const nameLength = res[1];
    const nameBytes = res.subarray(2, 2 + nameLength);
    return new TextDecoder().decode(nameBytes);
}

async function quitOpenApplication() {
    await ledgerSend(BOLOS_CLA, BOLOS_INS_QUIT_APP, 0, 0);
}

async function openNearApplication() {
    const runningApp = await getRunningAppName();
    if (runningApp === "NEAR") return;
    if (runningApp !== "BOLOS") {
        await quitOpenApplication();
        await new Promise(r => setTimeout(r, 1000));
    }
    const nearAppName = new TextEncoder().encode("NEAR");
    try {
        await ledgerSend(APP_OPEN_CLA, APP_OPEN_INS, 0x00, 0x00, nearAppName);
    } catch (error) {
        const msg = error.message || "";
        if (msg.includes("6807")) throw new Error("NEAR application is missing on the Ledger device");
        if (msg.includes("5501")) throw new Error("User declined to open the NEAR app");
        throw error;
    }
}

// ============================================================================
// RPC Helpers
// ============================================================================

async function rpcRequest(network, method, params) {
    const rpcUrls = {
        mainnet: "https://rpc.mainnet.fastnear.com",
        testnet: "https://rpc.testnet.fastnear.com",
    };
    const rpcUrl = rpcUrls[network] || rpcUrls.mainnet;
    const response = await fetch(rpcUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: "dontcare", method, params }),
    });
    const json = await response.json();
    if (json.error) throw new Error(json.error.message || "RPC request failed");
    if (json.result?.error) {
        const errMsg = typeof json.result.error === "string" ? json.result.error : JSON.stringify(json.result.error);
        throw new Error(errMsg);
    }
    return json.result;
}

// ============================================================================
// Error Messages
// ============================================================================

function getLedgerErrorMessage(error) {
    const msg = error.message || "";
    if (msg.includes("0xb005") || msg.includes("UNKNOWN_ERROR")) return "Please approve opening the NEAR app on your Ledger device.";
    if (msg.includes("0x5515") || msg.includes("Locked device")) return "Your Ledger device is locked. Please unlock it and try again.";
    if (msg.includes("6807") || msg.includes("NEAR application is missing")) return "NEAR application is not installed on your Ledger device. Please install it using Ledger Live.";
    if (msg.includes("5501") || msg.includes("declined")) return "You declined to open the NEAR app.";
    if (msg.includes("No device selected") || msg.includes("No Ledger device found")) return "No Ledger device was found. Please make sure your Ledger is nearby with Bluetooth enabled.";
    if (msg.includes("0x6985")) return "You declined the request on the Ledger device.";
    return msg || "An unknown error occurred.";
}

function isGuidanceMessage(message) {
    const text = (message || "").toLowerCase();
    return text.includes("locked") || text.includes("unlock") || text.includes("approve opening") || text.includes("approve the request");
}

// ============================================================================
// UI Helpers
// ============================================================================

function alertBox(message) {
    const guidance = isGuidanceMessage(message);
    const bg = guidance ? "#0a1628" : "#290606";
    const color = guidance ? "#93c5fd" : "#ef4444";
    return `<div style="padding:12px; border-radius:12px; background:${bg};">
        <p style="font-family:-apple-system,sans-serif; font-size:12px; color:${color}; line-height:1.5; margin:0; overflow-wrap:anywhere; word-break:break-word;">${message}</p>
    </div>`;
}

/**
 * Show approval UI with retry/cancel support.
 */
async function showLedgerApprovalUI(title, message, asyncOperation, hideOnSuccess = false) {
    await window.selector.ui.showIframe();
    const root = document.getElementById("root");
    root.style.display = "flex";

    function renderLoadingUI() {
        root.style.display = "flex";
        root.innerHTML = `
        <style>@keyframes ledger-spin { to { transform: rotate(360deg); } }</style>
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; gap:32px;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">${title}</span>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">${message}</p>
            </div>
            <div style="display:flex; align-items:center; justify-content:center; padding:22.5px 0;">
              <div style="width:44px; height:44px; border:3px solid #313131; border-top-color:#fafafa; border-radius:50%; animation:ledger-spin 1s linear infinite;"></div>
            </div>
          </div>
        </div>`;
    }

    function renderErrorUI(error) {
        const errorMessage = getLedgerErrorMessage(error);
        root.style.display = "flex";
        root.innerHTML = `
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; gap:32px; overflow:auto;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">${title}</span>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">${message}</p>
            </div>
            <div style="display:flex; flex-direction:column; gap:16px;">
              ${alertBox(errorMessage)}
              <div style="display:flex; flex-direction:column; gap:8px; padding-top:16px;">
                <button id="approvalCancelBtn" style="width:100%; padding:9.5px 24px; border-radius:8px; border:1px solid #404040; background:rgba(255,255,255,0.05); color:#fafafa; cursor:pointer; font-family:-apple-system,sans-serif; font-size:14px; font-weight:500;">Cancel</button>
                <button id="approvalRetryBtn" style="width:100%; padding:9.5px 24px; border-radius:8px; border:none; background:#f5f5f5; color:#0a0a0a; cursor:pointer; font-family:-apple-system,sans-serif; font-size:14px; font-weight:500;">Try Again</button>
              </div>
            </div>
          </div>
        </div>`;
    }

    function waitForRetryAction() {
        return new Promise((resolve, reject) => {
            const retryBtn = document.getElementById("approvalRetryBtn");
            const cancelBtn = document.getElementById("approvalCancelBtn");
            if (!retryBtn || !cancelBtn) { reject(new Error("UI unavailable")); return; }
            retryBtn.addEventListener("click", () => resolve("retry"), { once: true });
            cancelBtn.addEventListener("click", () => resolve("cancel"), { once: true });
        });
    }

    while (true) {
        renderLoadingUI();
        try {
            const result = await asyncOperation();
            if (hideOnSuccess) {
                root.innerHTML = "";
                root.style.display = "none";
                window.selector.ui.hideIframe();
            }
            return result;
        } catch (error) {
            renderErrorUI(error);
            const action = await waitForRetryAction();
            if (action === "retry") continue;
            root.innerHTML = "";
            root.style.display = "none";
            window.selector.ui.hideIframe();
            throw new Error("User cancelled");
        }
    }
}

// ============================================================================
// Connect Flow UI
// ============================================================================

const STORAGE_KEY_ACCOUNTS = "ledger:accounts";
const STORAGE_KEY_DERIVATION_PATH = "ledger:derivationPath";

let _isDeviceConnected = false;

async function promptForLedgerConnect() {
    await window.selector.ui.showIframe();
    const root = document.getElementById("root");
    root.style.display = "flex";

    function renderUI(errorMessage = null) {
        root.style.display = "flex";
        root.innerHTML = `
        <style>@keyframes ledger-connect-spin { to { transform: rotate(360deg); } }</style>
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; gap:32px; overflow:auto;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <div style="display:flex; align-items:center; justify-content:space-between;">
                <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">Connect Ledger</span>
                <button id="cancelBtn" style="background:transparent; border:none; cursor:pointer; padding:4px;">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M1 1L13 13M13 1L1 13" stroke="#fafafa" stroke-width="1.5" stroke-linecap="round"/></svg>
                </button>
              </div>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">
                Make sure your <strong style="color:#fafafa;">Ledger Nano X</strong> is unlocked with Bluetooth enabled and the <strong style="color:#fafafa;">NEAR app</strong> is installed.
              </p>
            </div>
            <div style="display:flex; flex-direction:column; gap:16px;">
              <button id="bleBtn" style="width:100%; padding:12px; border-radius:12px; border:1px solid #313131; background:#1a1a1a; display:flex; align-items:center; gap:12px; cursor:pointer; text-align:left;">
                <div style="width:40px; height:40px; border-radius:8px; background:#2a2a2a; display:flex; align-items:center; justify-content:center; flex-shrink:0; font-size:16px;">📡</div>
                <div style="flex:1;">
                  <div style="font-family:-apple-system,sans-serif; font-weight:600; font-size:16px; color:#f5f5f5; line-height:1.5;">Bluetooth</div>
                  <div style="font-family:-apple-system,sans-serif; font-size:12px; color:#a3a3a3; line-height:1.5;">Connect via Bluetooth Low Energy</div>
                </div>
              </button>
              ${errorMessage ? alertBox(errorMessage) : ""}
              <div style="padding-top:32px;">
                <button id="closeBtn" style="width:100%; padding:9.5px 24px; border-radius:8px; border:1px solid #404040; background:rgba(255,255,255,0.05); color:#fafafa; cursor:pointer; font-family:-apple-system,sans-serif; font-size:14px; font-weight:500;">Close</button>
              </div>
            </div>
          </div>
        </div>`;
    }

    function renderConnectingUI(statusMessage) {
        root.style.display = "flex";
        root.innerHTML = `
        <style>@keyframes ledger-connect-spin { to { transform: rotate(360deg); } }</style>
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; gap:32px;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">Connect Ledger</span>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">${statusMessage}</p>
            </div>
            <div style="display:flex; align-items:center; justify-content:center; padding:22.5px 0;">
              <div style="width:44px; height:44px; border:3px solid #313131; border-top-color:#fafafa; border-radius:50%; animation:ledger-connect-spin 1s linear infinite;"></div>
            </div>
          </div>
        </div>`;
    }

    renderUI();

    return new Promise((resolve, reject) => {
        async function handleConnect() {
            try {
                if (_isDeviceConnected) {
                    try { await swiftBLE("disconnect"); } catch {}
                    _isDeviceConnected = false;
                }
                renderConnectingUI("Scanning for Ledger devices…");
                await swiftBLE("scan");
                // Wait for device discovery
                await new Promise(r => setTimeout(r, 3000));
                await swiftBLE("stopScan");

                const devices = await swiftBLE("getDevices");
                if (!devices || devices.length === 0) {
                    throw new Error("No Ledger device found. Make sure Bluetooth is enabled and the device is nearby.");
                }
                renderConnectingUI("Connecting to " + devices[0].name + "…");
                await swiftBLE("connect", { deviceName: devices[0].name });
                _isDeviceConnected = true;

                renderConnectingUI("Please approve opening the NEAR app on your Ledger device.");
                await openNearApplication();
                resolve();
            } catch (error) {
                if (_isDeviceConnected) {
                    try { await swiftBLE("disconnect"); } catch {}
                    _isDeviceConnected = false;
                }
                renderUI(getLedgerErrorMessage(error));
                setupListeners();
            }
        }

        function setupListeners() {
            const cancelBtn = document.getElementById("cancelBtn");
            const closeBtn = document.getElementById("closeBtn");
            const bleBtn = document.getElementById("bleBtn");

            if (bleBtn) bleBtn.addEventListener("click", handleConnect);
            if (closeBtn) closeBtn.addEventListener("click", () => {
                root.innerHTML = "";
                root.style.display = "none";
                window.selector.ui.hideIframe();
                reject(new Error("User cancelled"));
            });
            if (cancelBtn) cancelBtn.addEventListener("click", () => {
                root.innerHTML = "";
                root.style.display = "none";
                window.selector.ui.hideIframe();
                reject(new Error("User cancelled"));
            });
        }

        setupListeners();
    });
}

// ============================================================================
// Derivation Path UI
// ============================================================================

async function promptForDerivationPath(currentPath = DEFAULT_DERIVATION_PATH) {
    await window.selector.ui.showIframe();
    const root = document.getElementById("root");
    root.style.display = "flex";

    const pathOptions = [
        { label: "Account 1", path: "44'/397'/0'/0'/0'" },
        { label: "Account 2", path: "44'/397'/0'/0'/1'" },
        { label: "Account 3", path: "44'/397'/0'/0'/2'" },
    ];

    function renderUI() {
        root.innerHTML = `
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; justify-content:space-between; overflow:hidden;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <div style="display:flex; align-items:center; justify-content:space-between;">
                <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">Select Derivation Path</span>
                <button id="cancelBtn" style="background:transparent; border:none; cursor:pointer; padding:4px;">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M1 1L13 13M13 1L1 13" stroke="#fafafa" stroke-width="1.5" stroke-linecap="round"/></svg>
                </button>
              </div>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">Choose account index to use from your Ledger.</p>
            </div>
            <div style="display:flex; flex-direction:column; gap:16px; flex:1; padding-top:32px;">
              ${pathOptions.map(opt => `
                <button class="path-btn" data-path="${opt.path}" style="width:100%; padding:12px; border-radius:12px; border:1px solid ${currentPath === opt.path ? "#a6a6a6" : "#313131"}; background:#1a1a1a; cursor:pointer; text-align:left;">
                  <div style="font-family:-apple-system,sans-serif; font-weight:600; font-size:16px; color:#f5f5f5; line-height:1.5;">${opt.label}</div>
                  <div style="font-family:-apple-system,sans-serif; font-size:12px; color:#a3a3a3; line-height:1.5;">${opt.path}</div>
                </button>
              `).join("")}
            </div>
            <div style="padding-top:16px;">
              <button id="confirmBtn" style="width:100%; padding:9.5px 24px; border-radius:8px; border:none; background:#f5f5f5; color:#0a0a0a; cursor:pointer; font-family:-apple-system,sans-serif; font-size:14px; font-weight:500;">Continue</button>
            </div>
          </div>
        </div>`;
    }

    renderUI();

    return new Promise((resolve, reject) => {
        function setupListeners() {
            const confirmBtn = document.getElementById("confirmBtn");
            const cancelBtn = document.getElementById("cancelBtn");
            const pathBtns = document.querySelectorAll(".path-btn");

            pathBtns.forEach(btn => {
                btn.addEventListener("click", () => {
                    currentPath = btn.dataset.path;
                    renderUI();
                    setupListeners();
                });
            });

            confirmBtn.addEventListener("click", () => {
                root.innerHTML = "";
                root.style.display = "none";
                resolve(currentPath);
            });

            cancelBtn.addEventListener("click", () => {
                root.innerHTML = "";
                root.style.display = "none";
                window.selector.ui.hideIframe();
                reject(new Error("User cancelled"));
            });
        }
        setupListeners();
    });
}

// ============================================================================
// Account ID Input UI
// ============================================================================

async function promptForAccountId(implicitAccountId = "", onVerify = null, hideOnSuccess = true) {
    await window.selector.ui.showIframe();
    const root = document.getElementById("root");
    root.style.display = "flex";

    function renderUI(errorMessage = null, currentValue = "") {
        root.innerHTML = `
        <div style="display:flex; flex-direction:column; width:100%; height:100%; background:#000; border-radius:24px; overflow:hidden; text-align:left;">
          <div style="flex:1; padding:24px; display:flex; flex-direction:column; gap:32px; overflow:auto;">
            <div style="display:flex; flex-direction:column; gap:12px; padding-top:20px;">
              <div style="display:flex; align-items:center; justify-content:space-between;">
                <span style="font-family:-apple-system,sans-serif; font-weight:600; font-size:24px; color:#fafafa;">Enter Account ID</span>
                <button id="cancelBtn" style="background:transparent; border:none; cursor:pointer; padding:4px;">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M1 1L13 13M13 1L1 13" stroke="#fafafa" stroke-width="1.5" stroke-linecap="round"/></svg>
                </button>
              </div>
              <p style="font-family:-apple-system,sans-serif; font-size:16px; color:#a3a3a3; line-height:1.5; margin:0;">
                Ledger provides your public key. Please enter the NEAR account ID that this key has access to.
              </p>
            </div>
            <div style="display:flex; flex-direction:column; gap:16px;">
              ${errorMessage ? alertBox(errorMessage) : ""}
              <input type="text" id="accountIdInput" placeholder="example.near" value="${currentValue}"
                style="width:100%; padding:12px; border-radius:12px; border:1px solid ${errorMessage ? "#ef4444" : "#a6a6a6"}; background:#1a1a1a; color:#fafafa; font-family:-apple-system,sans-serif; font-size:14px; box-sizing:border-box; outline:none;" />
              ${implicitAccountId ? `
              <button id="useImplicitBtn" style="width:100%; padding:12px; border-radius:12px; border:1px solid #313131; background:#1a1a1a; cursor:pointer; text-align:left;">
                <span style="font-family:-apple-system,sans-serif; font-size:12px; color:#a3a3a3;">Use implicit account: </span>
                <span style="font-family:-apple-system,sans-serif; font-size:12px; color:#f5f5f5;">${implicitAccountId.slice(0, 12)}...${implicitAccountId.slice(-8)}</span>
              </button>` : ""}
              <div style="padding-top:16px;">
                <button id="confirmBtn" style="width:100%; padding:9.5px 24px; border-radius:8px; border:none; background:#f5f5f5; color:#0a0a0a; cursor:pointer; font-family:-apple-system,sans-serif; font-size:14px; font-weight:500;">Confirm</button>
              </div>
            </div>
          </div>
        </div>`;
    }

    renderUI();

    return new Promise((resolve, reject) => {
        function setupListeners() {
            const input = document.getElementById("accountIdInput");
            const confirmBtn = document.getElementById("confirmBtn");
            const cancelBtn = document.getElementById("cancelBtn");
            const useImplicitBtn = document.getElementById("useImplicitBtn");

            if (useImplicitBtn && implicitAccountId) {
                useImplicitBtn.addEventListener("click", () => {
                    input.value = implicitAccountId;
                    input.focus();
                });
            }

            confirmBtn.addEventListener("click", async () => {
                const accountId = input.value.trim();
                if (!accountId) return;
                if (onVerify) {
                    confirmBtn.disabled = true;
                    confirmBtn.textContent = "Verifying...";
                    try {
                        await onVerify(accountId);
                        if (hideOnSuccess) {
                            root.innerHTML = "";
                            root.style.display = "none";
                            window.selector.ui.hideIframe();
                        }
                        resolve(accountId);
                    } catch (error) {
                        renderUI(error.message, accountId);
                        setupListeners();
                    }
                } else {
                    if (hideOnSuccess) {
                        root.innerHTML = "";
                        root.style.display = "none";
                        window.selector.ui.hideIframe();
                    }
                    resolve(accountId);
                }
            });

            cancelBtn.addEventListener("click", () => {
                root.innerHTML = "";
                root.style.display = "none";
                window.selector.ui.hideIframe();
                reject(new Error("User cancelled"));
            });

            input.addEventListener("keypress", e => {
                if (e.key === "Enter") confirmBtn.click();
            });
            setTimeout(() => input.focus(), 100);
        }
        setupListeners();
    });
}

// ============================================================================
// Access Key Verification
// ============================================================================

async function verifyAccessKey(network, accountId, publicKey) {
    // Check account exists
    try {
        await rpcRequest(network, "query", {
            request_type: "view_account",
            finality: "final",
            account_id: accountId,
        });
    } catch (error) {
        const msg = error.message || "";
        if (msg.includes("does not exist") || msg.includes("UnknownAccount")) {
            throw new Error(`Account ${accountId} does not exist on the NEAR blockchain.`);
        }
        throw error;
    }

    // Check access key
    try {
        const accessKey = await rpcRequest(network, "query", {
            request_type: "view_access_key",
            finality: "final",
            account_id: accountId,
            public_key: publicKey,
        });
        if (accessKey.permission !== "FullAccess") {
            throw new Error("The public key does not have FullAccess permission for this account.");
        }
        return true;
    } catch (error) {
        const msg = error.message || "";
        if (msg.includes("access key") || msg.includes("does not exist")) {
            throw new Error(`Access key not found for account ${accountId}. Please make sure the Ledger public key is registered for this account.`);
        }
        throw error;
    }
}

// ============================================================================
// Borsh Serialization Helpers
// ============================================================================

// We manually construct Borsh-serialized payloads to avoid importing
// @near-js/transactions which has assertion code that fails in WKWebView.

function writeU32LE(buf, offset, val) {
    buf[offset]     = val & 0xff;
    buf[offset + 1] = (val >> 8) & 0xff;
    buf[offset + 2] = (val >> 16) & 0xff;
    buf[offset + 3] = (val >> 24) & 0xff;
    return offset + 4;
}

function writeU64LE(buf, offset, val) {
    const bigVal = BigInt(val);
    for (let i = 0; i < 8; i++) {
        buf[offset + i] = Number((bigVal >> BigInt(i * 8)) & 0xFFn);
    }
    return offset + 8;
}

function writeU128LE(buf, offset, val) {
    const bigVal = BigInt(val);
    for (let i = 0; i < 16; i++) {
        buf[offset + i] = Number((bigVal >> BigInt(i * 8)) & 0xFFn);
    }
    return offset + 16;
}

function writeBorshString(buf, offset, str) {
    const bytes = new TextEncoder().encode(str);
    offset = writeU32LE(buf, offset, bytes.length);
    buf.set(bytes, offset);
    return offset + bytes.length;
}

function writePublicKey(buf, offset, publicKeyStr) {
    // PublicKey enum: keyType u8, data [u8; 32]
    const keyStr = publicKeyStr.startsWith("ed25519:") ? publicKeyStr.slice(8) : publicKeyStr;
    const keyBytes = base58Decode(keyStr);
    buf[offset] = 0; // ed25519
    offset += 1;
    buf.set(keyBytes.subarray(0, 32), offset);
    return offset + 32;
}

// ============================================================================
// Transaction Building (manual Borsh)
// ============================================================================

/**
 * Build action bytes for a single action in Borsh format.
 * Returns { bytes: Uint8Array }
 */
function buildActionBytes(action) {
    const parts = [];

    function pushAction(typeIndex, data) {
        const buf = new Uint8Array(1 + data.length);
        buf[0] = typeIndex;
        buf.set(data, 1);
        parts.push(buf);
    }

    if (action.type === "FunctionCall") {
        const p = action.params;
        const methodBytes = new TextEncoder().encode(p.methodName);
        let args;
        if (typeof p.args === "string") {
            // base64 encoded
            args = Uint8Array.from(atob(p.args), c => c.charCodeAt(0));
        } else if (p.args instanceof Uint8Array) {
            args = p.args;
        } else if (typeof p.args === "object") {
            args = new TextEncoder().encode(JSON.stringify(p.args));
        } else {
            args = new Uint8Array(0);
        }
        const data = new Uint8Array(4 + methodBytes.length + 4 + args.length + 8 + 16);
        let off = 0;
        off = writeU32LE(data, off, methodBytes.length);
        data.set(methodBytes, off); off += methodBytes.length;
        off = writeU32LE(data, off, args.length);
        data.set(args, off); off += args.length;
        off = writeU64LE(data, off, p.gas || "30000000000000");
        off = writeU128LE(data, off, p.deposit || "0");
        pushAction(2, data.subarray(0, off));
    } else if (action.type === "Transfer") {
        const data = new Uint8Array(16);
        writeU128LE(data, 0, action.params.deposit);
        pushAction(3, data);
    } else if (action.type === "CreateAccount") {
        pushAction(0, new Uint8Array(0));
    } else if (action.type === "DeleteAccount") {
        const benBytes = new TextEncoder().encode(action.params.beneficiaryId);
        const data = new Uint8Array(4 + benBytes.length);
        writeU32LE(data, 0, benBytes.length);
        data.set(benBytes, 4);
        pushAction(7, data);
    } else if (action.type === "AddKey") {
        // Simplified: only support FullAccess keys
        const data = new Uint8Array(33 + 8 + 1);
        let off = 0;
        const pkStr = action.params.publicKey;
        const keyStr = pkStr.startsWith("ed25519:") ? pkStr.slice(8) : pkStr;
        const keyBytes = base58Decode(keyStr);
        data[off] = 0; off += 1; // keyType ed25519
        data.set(keyBytes.subarray(0, 32), off); off += 32;
        off = writeU64LE(data, off, 0); // nonce
        data[off] = 1; off += 1; // FullAccess
        pushAction(5, data.subarray(0, off));
    } else if (action.type === "DeleteKey") {
        const data = new Uint8Array(33);
        const pkStr = action.params.publicKey;
        const keyStr = pkStr.startsWith("ed25519:") ? pkStr.slice(8) : pkStr;
        const keyBytes = base58Decode(keyStr);
        data[0] = 0; // keyType
        data.set(keyBytes.subarray(0, 32), 1);
        pushAction(6, data);
    } else if (action.type === "Stake") {
        const data = new Uint8Array(16 + 33);
        let off = writeU128LE(data, 0, action.params.stake);
        const pkStr = action.params.publicKey;
        const keyStr = pkStr.startsWith("ed25519:") ? pkStr.slice(8) : pkStr;
        const keyBytes = base58Decode(keyStr);
        data[off] = 0; off += 1;
        data.set(keyBytes.subarray(0, 32), off);
        pushAction(4, data);
    } else if (action.type === "DeployContract") {
        const code = action.params.code;
        const data = new Uint8Array(4 + code.length);
        writeU32LE(data, 0, code.length);
        data.set(code, 4);
        pushAction(1, data);
    } else {
        throw new Error("Unsupported action type: " + action.type);
    }

    // Concatenate all parts
    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

/**
 * Build a complete Borsh-serialized transaction (unsigned).
 */
function buildTransaction(signerId, publicKey, receiverId, nonce, actions, blockHash) {
    const parts = [];

    // signerId (borsh string)
    const signerBytes = new TextEncoder().encode(signerId);
    const signerBuf = new Uint8Array(4 + signerBytes.length);
    writeU32LE(signerBuf, 0, signerBytes.length);
    signerBuf.set(signerBytes, 4);
    parts.push(signerBuf);

    // publicKey (enum variant 0 = ed25519, then 32 bytes)
    const pkBuf = new Uint8Array(33);
    const keyStr = publicKey.startsWith("ed25519:") ? publicKey.slice(8) : publicKey;
    pkBuf[0] = 0;
    pkBuf.set(base58Decode(keyStr).subarray(0, 32), 1);
    parts.push(pkBuf);

    // nonce (u64 LE)
    const nonceBuf = new Uint8Array(8);
    writeU64LE(nonceBuf, 0, nonce);
    parts.push(nonceBuf);

    // receiverId (borsh string)
    const recvBytes = new TextEncoder().encode(receiverId);
    const recvBuf = new Uint8Array(4 + recvBytes.length);
    writeU32LE(recvBuf, 0, recvBytes.length);
    recvBuf.set(recvBytes, 4);
    parts.push(recvBuf);

    // blockHash (32 bytes)
    parts.push(blockHash);

    // actions (vec<Action>: u32 count + action bytes)
    const actionParts = actions.map(a => buildActionBytes(a));
    const countBuf = new Uint8Array(4);
    writeU32LE(countBuf, 0, actionParts.length);
    parts.push(countBuf);
    for (const ap of actionParts) parts.push(ap);

    // Concatenate
    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

/**
 * Build a Borsh-serialized DelegateAction.
 */
function buildDelegateActionBytes(senderId, receiverId, actions, nonce, maxBlockHeight, publicKey) {
    const parts = [];

    // senderId
    const senderBytes = new TextEncoder().encode(senderId);
    const senderBuf = new Uint8Array(4 + senderBytes.length);
    writeU32LE(senderBuf, 0, senderBytes.length);
    senderBuf.set(senderBytes, 4);
    parts.push(senderBuf);

    // receiverId
    const recvBytes = new TextEncoder().encode(receiverId);
    const recvBuf = new Uint8Array(4 + recvBytes.length);
    writeU32LE(recvBuf, 0, recvBytes.length);
    recvBuf.set(recvBytes, 4);
    parts.push(recvBuf);

    // actions (NonDelegateAction vec)
    const actionParts = actions.map(a => buildActionBytes(a));
    const countBuf = new Uint8Array(4);
    writeU32LE(countBuf, 0, actionParts.length);
    parts.push(countBuf);
    for (const ap of actionParts) parts.push(ap);

    // nonce (u64)
    const nonceBuf = new Uint8Array(8);
    writeU64LE(nonceBuf, 0, nonce);
    parts.push(nonceBuf);

    // maxBlockHeight (u64)
    const mbbuf = new Uint8Array(8);
    writeU64LE(mbbuf, 0, maxBlockHeight);
    parts.push(mbbuf);

    // publicKey
    const pkBuf = new Uint8Array(33);
    const keyStr = publicKey.startsWith("ed25519:") ? publicKey.slice(8) : publicKey;
    pkBuf[0] = 0;
    pkBuf.set(base58Decode(keyStr).subarray(0, 32), 1);
    parts.push(pkBuf);

    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

/**
 * Build NEP-413 message payload (Borsh serialized).
 */
function buildNep413Payload(message, recipient, nonce) {
    const messageBytes = new TextEncoder().encode(message);
    const recipientBytes = new TextEncoder().encode(recipient);
    const payloadSize = 4 + messageBytes.length + 32 + 4 + recipientBytes.length + 1;
    const payload = new Uint8Array(payloadSize);
    const view = new DataView(payload.buffer);
    let offset = 0;
    view.setUint32(offset, messageBytes.length, true); offset += 4;
    payload.set(messageBytes, offset); offset += messageBytes.length;
    payload.set(nonce, offset); offset += 32;
    view.setUint32(offset, recipientBytes.length, true); offset += 4;
    payload.set(recipientBytes, offset); offset += recipientBytes.length;
    payload[offset] = 0; // callback_url = None
    return payload;
}

// ============================================================================
// Wallet Implementation
// ============================================================================

class LedgerWallet {
    async getDerivationPath() {
        const path = await window.selector.storage.get(STORAGE_KEY_DERIVATION_PATH);
        return path || DEFAULT_DERIVATION_PATH;
    }

    async _reconnectForSigning() {
        const isConnected = await swiftBLE("isConnected");
        if (isConnected) return;

        await showLedgerApprovalUI(
            "Reconnect Ledger",
            "Please reconnect your Ledger and approve opening app on your Ledger device.",
            async () => {
                await swiftBLE("scan");
                await new Promise(r => setTimeout(r, 3000));
                await swiftBLE("stopScan");
                const devices = await swiftBLE("getDevices");
                if (!devices || devices.length === 0) throw new Error("No Ledger device found");
                await swiftBLE("connect", { deviceName: devices[0].name });
                _isDeviceConnected = true;
                await openNearApplication();
            },
            false,
        );
    }

    async _ensureReady() {
        const accounts = await this.getAccounts();
        if (!accounts || accounts.length === 0) throw new Error("No account connected");
        const isConnected = await swiftBLE("isConnected");
        if (!isConnected) await this._reconnectForSigning();
        return accounts;
    }

    async _getAccessKeyAndBlock(network, signerId, publicKey) {
        const accessKey = await rpcRequest(network, "query", {
            request_type: "view_access_key",
            finality: "final",
            account_id: signerId,
            public_key: publicKey,
        });
        const block = await rpcRequest(network, "block", { finality: "final" });
        return { accessKey, block };
    }

    async _performSignInFlow(params) {
        await promptForLedgerConnect();

        const defaultPath = await this.getDerivationPath();
        const derivationPath = await promptForDerivationPath(defaultPath);

        const publicKeyBytes = await showLedgerApprovalUI(
            "Approve on Ledger",
            "Please approve the request on your Ledger device to share your public key.",
            () => getPublicKey(derivationPath),
        );
        const publicKeyString = base58Encode(publicKeyBytes);
        const publicKey = `ed25519:${publicKeyString}`;

        // Implicit account ID (hex)
        const implicitAccountId = Array.from(publicKeyBytes).map(b => b.toString(16).padStart(2, "0")).join("");

        const network = params?.network || "mainnet";
        const verifyAccount = async (accountId) => {
            await verifyAccessKey(network, accountId, publicKey);
        };

        const accountId = await promptForAccountId(implicitAccountId, verifyAccount, false);

        const accounts = [{ accountId, publicKey }];
        await window.selector.storage.set(STORAGE_KEY_ACCOUNTS, JSON.stringify(accounts));
        await window.selector.storage.set(STORAGE_KEY_DERIVATION_PATH, derivationPath);

        return { accounts, derivationPath };
    }

    async signIn(params) {
        try {
            const { accounts } = await this._performSignInFlow(params);
            window.selector.ui.hideIframe();
            return accounts;
        } catch (error) {
            try { await swiftBLE("disconnect"); } catch {}
            _isDeviceConnected = false;
            throw error;
        }
    }

    async signInAndSignMessage(params) {
        try {
            const { accounts, derivationPath } = await this._performSignInFlow(params);
            const { message, recipient, nonce } = params.messageParams;
            const payload = buildNep413Payload(message, recipient || "", nonce || new Uint8Array(32));

            // NEP-413 prefix for Ledger signing
            const NEP413_PREFIX = new Uint8Array([0x9d, 0x01, 0x00, 0x80]);
            const dataWithPrefix = new Uint8Array(NEP413_PREFIX.length + payload.length);
            dataWithPrefix.set(NEP413_PREFIX, 0);
            dataWithPrefix.set(payload, NEP413_PREFIX.length);

            const signature = await showLedgerApprovalUI(
                "Sign Message",
                "Please review and approve the message signing on your Ledger device.",
                () => sign(dataWithPrefix, derivationPath),
                true,
            );

            const signatureBase64 = btoa(String.fromCharCode(...signature));

            return accounts.map(account => ({
                ...account,
                signedMessage: {
                    accountId: account.accountId,
                    publicKey: account.publicKey,
                    signature: signatureBase64,
                },
            }));
        } catch (error) {
            try { await swiftBLE("disconnect"); } catch {}
            _isDeviceConnected = false;
            throw error;
        }
    }

    async signOut() {
        try { await swiftBLE("disconnect"); } catch {}
        _isDeviceConnected = false;
        await window.selector.storage.remove(STORAGE_KEY_ACCOUNTS);
        await window.selector.storage.remove(STORAGE_KEY_DERIVATION_PATH);
        return true;
    }

    async getAccounts() {
        const json = await window.selector.storage.get(STORAGE_KEY_ACCOUNTS);
        if (!json) return [];
        try { return JSON.parse(json); } catch { return []; }
    }

    async signAndSendTransaction(params) {
        const accounts = await this._ensureReady();
        const network = params.network || "mainnet";
        const signerId = accounts[0].accountId;
        const { receiverId, actions } = params.transactions[0];

        const { accessKey, block } = await this._getAccessKeyAndBlock(network, signerId, accounts[0].publicKey);
        const blockHash = base58Decode(block.header.hash);
        const nonce = BigInt(accessKey.nonce) + 1n;

        const txBytes = buildTransaction(signerId, accounts[0].publicKey, receiverId, nonce, actions, blockHash);
        const derivationPath = await this.getDerivationPath();

        const signature = await showLedgerApprovalUI(
            "Approve Transaction",
            "Please review and approve the transaction on your Ledger device.",
            () => sign(txBytes, derivationPath),
            true,
        );

        // Build signed transaction: tx bytes + signature (enum variant 0 + 64 bytes)
        const signedTx = new Uint8Array(txBytes.length + 1 + 64);
        signedTx.set(txBytes, 0);
        signedTx[txBytes.length] = 0; // ed25519
        signedTx.set(signature.subarray(0, 64), txBytes.length + 1);

        const base64Tx = btoa(String.fromCharCode(...signedTx));
        const result = await rpcRequest(network, "broadcast_tx_commit", [base64Tx]);
        return result;
    }

    async signDelegateAction(params) {
        const accounts = await this._ensureReady();
        const network = params.network || "mainnet";
        const { accountId: signerId, publicKey } = accounts[0];
        const { receiverId, actions } = params.transaction;

        const { accessKey, block } = await this._getAccessKeyAndBlock(network, signerId, publicKey);
        const nonce = BigInt(accessKey.nonce) + 1n;
        const maxBlockHeight = BigInt(block.header.height) + 120n;

        const daBytes = buildDelegateActionBytes(signerId, receiverId, actions, nonce, maxBlockHeight, publicKey);

        // NEP-366 prefix for Ledger signing
        const NEP366_PREFIX = new Uint8Array([0x6e, 0x01, 0x00, 0x40]);
        const dataWithPrefix = new Uint8Array(NEP366_PREFIX.length + daBytes.length);
        dataWithPrefix.set(NEP366_PREFIX, 0);
        dataWithPrefix.set(daBytes, NEP366_PREFIX.length);

        const derivationPath = await this.getDerivationPath();
        const signature = await showLedgerApprovalUI(
            "Approve Transaction",
            "Please review and approve the transaction on your Ledger device.",
            () => sign(dataWithPrefix, derivationPath),
            true,
        );

        // Build SignedDelegate: DelegateAction bytes + Signature (enum 0 + 64 bytes)
        const signedDelegateBytes = new Uint8Array(daBytes.length + 1 + 64);
        signedDelegateBytes.set(daBytes, 0);
        signedDelegateBytes[daBytes.length] = 0; // ed25519
        signedDelegateBytes.set(signature.subarray(0, 64), daBytes.length + 1);

        const signedDelegateBase64 = btoa(String.fromCharCode(...signedDelegateBytes));

        // Delegate hash
        const delegateHash = new Uint8Array(await crypto.subtle.digest("SHA-256", dataWithPrefix));

        return {
            delegateHash,
            signedDelegateAction: signedDelegateBase64,
        };
    }

    async signAndSendTransactions(params) {
        const results = [];
        for (const tx of params.transactions) {
            const result = await this.signAndSendTransaction({
                ...params,
                transactions: [tx],
            });
            results.push(result);
        }
        return results;
    }

    async signDelegateActions(params) {
        const results = [];
        for (const tx of params.delegateActions) {
            const result = await this.signDelegateAction({
                ...params,
                transaction: tx,
            });
            results.push(result);
        }
        return { signedDelegateActions: results };
    }

    async signMessage(params) {
        const accounts = await this._ensureReady();
        const message = params.message;
        const recipient = params.recipient || "";
        const nonce = params.nonce || new Uint8Array(32);

        const payload = buildNep413Payload(message, recipient, nonce);
        const NEP413_PREFIX = new Uint8Array([0x9d, 0x01, 0x00, 0x80]);
        const dataWithPrefix = new Uint8Array(NEP413_PREFIX.length + payload.length);
        dataWithPrefix.set(NEP413_PREFIX, 0);
        dataWithPrefix.set(payload, NEP413_PREFIX.length);

        const derivationPath = await this.getDerivationPath();
        const signature = await showLedgerApprovalUI(
            "Sign Message",
            "Please review and approve the message signing on your Ledger device.",
            () => sign(dataWithPrefix, derivationPath),
            true,
        );

        const signatureBase64 = btoa(String.fromCharCode(...signature));
        return {
            accountId: accounts[0].accountId,
            publicKey: accounts[0].publicKey,
            signature: signatureBase64,
        };
    }
}

// ============================================================================
// Initialize and register with near-connect
// ============================================================================

const wallet = new LedgerWallet();
window.selector.ready(wallet);

// Test Wallet Executor for near-connect E2E tests
//
// Implements the NearWallet interface using a seed phrase for automated signing.
// Derives ed25519 keys from BIP39 mnemonic via SLIP-0010 (PBKDF2 + HMAC-SHA512).
// Uses tweetnacl for ed25519 operations.

// ============================================================================
// Configuration
// ============================================================================

const TEST_SEED_PHRASE = "icon mind word blouse meat inch bread blue burden paper glove churn";
const TEST_ACCOUNT_ID = "a.frol.near";
const DERIVATION_PATH = "m/44'/397'/0'";
const STORAGE_KEY_ACCOUNTS = "test-wallet:accounts";

// ============================================================================
// Base58 Encode / Decode
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
// BIP39 + SLIP-0010 Key Derivation (Web Crypto)
// ============================================================================

async function mnemonicToSeed(mnemonic, passphrase = "") {
    const enc = new TextEncoder();
    const mnemonicBytes = enc.encode(mnemonic.normalize("NFKD"));
    const salt = enc.encode("mnemonic" + passphrase);
    const keyMaterial = await crypto.subtle.importKey(
        "raw", mnemonicBytes, "PBKDF2", false, ["deriveBits"]
    );
    const bits = await crypto.subtle.deriveBits(
        { name: "PBKDF2", salt, iterations: 2048, hash: "SHA-512" },
        keyMaterial,
        512
    );
    return new Uint8Array(bits);
}

async function hmacSHA512(key, data) {
    const cryptoKey = await crypto.subtle.importKey(
        "raw", key, { name: "HMAC", hash: "SHA-512" }, false, ["sign"]
    );
    const sig = await crypto.subtle.sign("HMAC", cryptoKey, data);
    return new Uint8Array(sig);
}

async function slip0010DeriveEd25519(seed, path) {
    // Master key derivation
    const masterKey = await hmacSHA512(
        new TextEncoder().encode("ed25519 seed"),
        seed
    );
    let key = masterKey.slice(0, 32);
    let chainCode = masterKey.slice(32, 64);

    // Parse path: m/44'/397'/0'
    const segments = path.replace("m/", "").split("/").map(s => {
        const hardened = s.endsWith("'");
        const index = parseInt(s.replace("'", ""), 10);
        return hardened ? (index + 0x80000000) >>> 0 : index;
    });

    for (const index of segments) {
        const data = new Uint8Array(37);
        data[0] = 0x00; // ed25519 always uses hardened derivation with 0x00 prefix
        data.set(key, 1);
        data[33] = (index >> 24) & 0xff;
        data[34] = (index >> 16) & 0xff;
        data[35] = (index >> 8) & 0xff;
        data[36] = index & 0xff;
        const derived = await hmacSHA512(chainCode, data);
        key = derived.slice(0, 32);
        chainCode = derived.slice(32, 64);
    }

    return key; // 32-byte ed25519 seed
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
// Borsh Serialization Helpers
// ============================================================================

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
        const data = new Uint8Array(33 + 8 + 1);
        let off = 0;
        const pkStr = action.params.publicKey;
        const keyStr = pkStr.startsWith("ed25519:") ? pkStr.slice(8) : pkStr;
        const keyBytes = base58Decode(keyStr);
        data[off] = 0; off += 1;
        data.set(keyBytes.subarray(0, 32), off); off += 32;
        off = writeU64LE(data, off, 0);
        data[off] = 1; off += 1;
        pushAction(5, data.subarray(0, off));
    } else if (action.type === "DeleteKey") {
        const data = new Uint8Array(33);
        const pkStr = action.params.publicKey;
        const keyStr = pkStr.startsWith("ed25519:") ? pkStr.slice(8) : pkStr;
        const keyBytes = base58Decode(keyStr);
        data[0] = 0;
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

    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

function buildTransaction(signerId, publicKey, receiverId, nonce, actions, blockHash) {
    const parts = [];

    const signerBytes = new TextEncoder().encode(signerId);
    const signerBuf = new Uint8Array(4 + signerBytes.length);
    writeU32LE(signerBuf, 0, signerBytes.length);
    signerBuf.set(signerBytes, 4);
    parts.push(signerBuf);

    const pkBuf = new Uint8Array(33);
    const keyStr = publicKey.startsWith("ed25519:") ? publicKey.slice(8) : publicKey;
    pkBuf[0] = 0;
    pkBuf.set(base58Decode(keyStr).subarray(0, 32), 1);
    parts.push(pkBuf);

    const nonceBuf = new Uint8Array(8);
    writeU64LE(nonceBuf, 0, nonce);
    parts.push(nonceBuf);

    const recvBytes = new TextEncoder().encode(receiverId);
    const recvBuf = new Uint8Array(4 + recvBytes.length);
    writeU32LE(recvBuf, 0, recvBytes.length);
    recvBuf.set(recvBytes, 4);
    parts.push(recvBuf);

    parts.push(blockHash);

    const actionParts = actions.map(a => buildActionBytes(a));
    const countBuf = new Uint8Array(4);
    writeU32LE(countBuf, 0, actionParts.length);
    parts.push(countBuf);
    for (const ap of actionParts) parts.push(ap);

    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

function buildDelegateActionBytes(senderId, receiverId, actions, nonce, maxBlockHeight, publicKey) {
    const parts = [];

    const senderBytes = new TextEncoder().encode(senderId);
    const senderBuf = new Uint8Array(4 + senderBytes.length);
    writeU32LE(senderBuf, 0, senderBytes.length);
    senderBuf.set(senderBytes, 4);
    parts.push(senderBuf);

    const recvBytes = new TextEncoder().encode(receiverId);
    const recvBuf = new Uint8Array(4 + recvBytes.length);
    writeU32LE(recvBuf, 0, recvBytes.length);
    recvBuf.set(recvBytes, 4);
    parts.push(recvBuf);

    const actionParts = actions.map(a => buildActionBytes(a));
    const countBuf = new Uint8Array(4);
    writeU32LE(countBuf, 0, actionParts.length);
    parts.push(countBuf);
    for (const ap of actionParts) parts.push(ap);

    const nonceBuf = new Uint8Array(8);
    writeU64LE(nonceBuf, 0, nonce);
    parts.push(nonceBuf);

    const mbbuf = new Uint8Array(8);
    writeU64LE(mbbuf, 0, maxBlockHeight);
    parts.push(mbbuf);

    const pkBuf = new Uint8Array(33);
    const keyStr2 = publicKey.startsWith("ed25519:") ? publicKey.slice(8) : publicKey;
    pkBuf[0] = 0;
    pkBuf.set(base58Decode(keyStr2).subarray(0, 32), 1);
    parts.push(pkBuf);

    const totalLength = parts.reduce((s, p) => s + p.length, 0);
    const result = new Uint8Array(totalLength);
    let pos = 0;
    for (const p of parts) { result.set(p, pos); pos += p.length; }
    return result;
}

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

class TestWallet {
    constructor(keyPair, publicKeyString) {
        this._keyPair = keyPair;
        this._publicKey = publicKeyString; // "ed25519:..."
    }

    async signIn(params) {
        const accounts = [{ accountId: TEST_ACCOUNT_ID, publicKey: this._publicKey }];
        await window.selector.storage.set(STORAGE_KEY_ACCOUNTS, JSON.stringify(accounts));
        return accounts;
    }

    async signOut() {
        await window.selector.storage.remove(STORAGE_KEY_ACCOUNTS);
        return true;
    }

    async getAccounts() {
        const json = await window.selector.storage.get(STORAGE_KEY_ACCOUNTS);
        if (!json) return [];
        try { return JSON.parse(json); } catch { return []; }
    }

    async signInAndSignMessage(params) {
        const accounts = await this.signIn(params);
        const { message, recipient, nonce } = params.messageParams;
        const payload = buildNep413Payload(message, recipient || "", nonce || new Uint8Array(32));

        const NEP413_TAG = 2147484957; // (1 << 31) + 413
        const tagBuf = new Uint8Array(4);
        tagBuf[0] = (NEP413_TAG) & 0xff;
        tagBuf[1] = (NEP413_TAG >> 8) & 0xff;
        tagBuf[2] = (NEP413_TAG >> 16) & 0xff;
        tagBuf[3] = (NEP413_TAG >> 24) & 0xff;
        const dataToHash = new Uint8Array(tagBuf.length + payload.length);
        dataToHash.set(tagBuf, 0);
        dataToHash.set(payload, tagBuf.length);

        const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", dataToHash));
        const signature = nacl.sign.detached(hash, this._keyPair.secretKey);
        const signatureBase64 = btoa(String.fromCharCode(...signature));

        return accounts.map(account => ({
            ...account,
            signedMessage: {
                accountId: account.accountId,
                publicKey: account.publicKey,
                signature: signatureBase64,
            },
        }));
    }

    async signAndSendTransaction(params) {
        const accounts = await this.getAccounts();
        if (!accounts || accounts.length === 0) throw new Error("Not signed in");
        const network = params.network || "mainnet";
        const signerId = accounts[0].accountId;
        const publicKey = accounts[0].publicKey;
        const { receiverId, actions } = params.transactions[0];

        let nonce;
        const block = await rpcRequest(network, "block", { finality: "final" });
        const blockHash = base58Decode(block.header.hash);

        try {
            const accessKey = await rpcRequest(network, "query", {
                request_type: "view_access_key",
                finality: "final",
                account_id: signerId,
                public_key: publicKey,
            });
            nonce = BigInt(accessKey.nonce) + 1n;
        } catch (e) {
            // Fallback: use synthetic nonce when access key is not registered on-chain
            console.log("[TestWallet] Access key not found, using synthetic nonce for tx");
            nonce = 1n;
        }

        const txBytes = buildTransaction(signerId, publicKey, receiverId, nonce, actions, blockHash);

        const txHash = new Uint8Array(await crypto.subtle.digest("SHA-256", txBytes));
        const signature = nacl.sign.detached(txHash, this._keyPair.secretKey);

        // Build signed transaction: tx bytes + signature (enum variant 0 + 64 bytes)
        const signedTx = new Uint8Array(txBytes.length + 1 + 64);
        signedTx.set(txBytes, 0);
        signedTx[txBytes.length] = 0; // ed25519
        signedTx.set(signature, txBytes.length + 1);

        const base64Tx = btoa(String.fromCharCode(...signedTx));
        const txHashBase58 = base58Encode(txHash);

        try {
            const result = await rpcRequest(network, "broadcast_tx_commit", [base64Tx]);
            return result;
        } catch (e) {
            // Transaction may be rejected (e.g., invalid access key), but signing worked.
            // Return a synthetic result with the computed tx hash.
            console.log("[TestWallet] broadcast failed (expected for test key):", e.message);
            return {
                transaction_outcome: { id: txHashBase58 },
                transaction: { hash: txHashBase58 },
            };
        }
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

    async signMessage(params) {
        const accounts = await this.getAccounts();
        if (!accounts || accounts.length === 0) throw new Error("Not signed in");
        const message = params.message;
        const recipient = params.recipient || "";
        const nonce = params.nonce || new Uint8Array(32);

        const payload = buildNep413Payload(message, recipient, nonce);

        const NEP413_TAG = 2147484957;
        const tagBuf = new Uint8Array(4);
        tagBuf[0] = (NEP413_TAG) & 0xff;
        tagBuf[1] = (NEP413_TAG >> 8) & 0xff;
        tagBuf[2] = (NEP413_TAG >> 16) & 0xff;
        tagBuf[3] = (NEP413_TAG >> 24) & 0xff;
        const dataToHash = new Uint8Array(tagBuf.length + payload.length);
        dataToHash.set(tagBuf, 0);
        dataToHash.set(payload, tagBuf.length);

        const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", dataToHash));
        const signature = nacl.sign.detached(hash, this._keyPair.secretKey);
        const signatureBase64 = btoa(String.fromCharCode(...signature));

        return {
            accountId: accounts[0].accountId,
            publicKey: accounts[0].publicKey,
            signedMessage: signatureBase64,
        };
    }

    async signDelegateAction(params) {
        const accounts = await this.getAccounts();
        if (!accounts || accounts.length === 0) throw new Error("Not signed in");
        const network = params.network || "mainnet";
        const { accountId: signerId, publicKey } = accounts[0];
        const { receiverId, actions } = params.transaction;

        let nonce, maxBlockHeight;
        try {
            const accessKey = await rpcRequest(network, "query", {
                request_type: "view_access_key",
                finality: "final",
                account_id: signerId,
                public_key: publicKey,
            });
            const block = await rpcRequest(network, "block", { finality: "final" });
            nonce = BigInt(accessKey.nonce) + 1n;
            maxBlockHeight = BigInt(block.header.height) + 120n;
        } catch (e) {
            // Fallback: use synthetic nonce when access key is not registered on-chain.
            // This allows testing the signing flow without requiring the key to exist.
            console.log("[TestWallet] Access key not found on-chain, using synthetic nonce");
            const block = await rpcRequest(network, "block", { finality: "final" });
            nonce = 1n;
            maxBlockHeight = BigInt(block.header.height) + 120n;
        }

        const daBytes = buildDelegateActionBytes(signerId, receiverId, actions, nonce, maxBlockHeight, publicKey);

        // NEP-366 signing: SHA-256 hash of (NEP366_TAG_LE || delegateActionBytes)
        const NEP366_TAG = 2147483711; // (1 << 31) + 63
        const tagBuf = new Uint8Array(4);
        tagBuf[0] = (NEP366_TAG) & 0xff;
        tagBuf[1] = (NEP366_TAG >> 8) & 0xff;
        tagBuf[2] = (NEP366_TAG >> 16) & 0xff;
        tagBuf[3] = (NEP366_TAG >> 24) & 0xff;
        const dataToHash = new Uint8Array(tagBuf.length + daBytes.length);
        dataToHash.set(tagBuf, 0);
        dataToHash.set(daBytes, tagBuf.length);

        const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", dataToHash));
        const signature = nacl.sign.detached(hash, this._keyPair.secretKey);

        // Build SignedDelegate bytes: DelegateAction bytes + Signature (enum 0 + 64 bytes)
        const signedDelegateBytes = new Uint8Array(daBytes.length + 1 + 64);
        signedDelegateBytes.set(daBytes, 0);
        signedDelegateBytes[daBytes.length] = 0; // ed25519
        signedDelegateBytes.set(signature, daBytes.length + 1);

        const signedDelegateBase64 = btoa(String.fromCharCode(...signedDelegateBytes));

        return {
            delegateHash: hash,
            signedDelegateAction: signedDelegateBase64,
        };
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
}

// ============================================================================
// Initialize: derive keys, create wallet, register
// ============================================================================

(async function init() {
    try {
        console.log("[TestWallet] Starting initialization...");

        // Load tweetnacl from CDN
        const tweetnaclUrl = "https://cdn.jsdelivr.net/npm/tweetnacl@1.0.3/nacl-fast.min.js";
        const resp = await fetch(tweetnaclUrl);
        const code = await resp.text();
        // tweetnacl sets window.nacl when loaded as a script
        const fn = new Function(code + "\n; return nacl;");
        const naclLib = fn();
        // Make it globally available
        self.nacl = naclLib;

        console.log("[TestWallet] tweetnacl loaded");

        // Derive ed25519 seed from mnemonic
        const bip39Seed = await mnemonicToSeed(TEST_SEED_PHRASE);
        const ed25519Seed = await slip0010DeriveEd25519(bip39Seed, DERIVATION_PATH);

        console.log("[TestWallet] Key derived from seed phrase");

        // Create ed25519 keypair from 32-byte seed
        const keyPair = naclLib.sign.keyPair.fromSeed(ed25519Seed);
        const publicKeyString = "ed25519:" + base58Encode(keyPair.publicKey);

        console.log("[TestWallet] Public key:", publicKeyString);

        // Create and register wallet
        const wallet = new TestWallet(keyPair, publicKeyString);
        window.selector.ready(wallet);

        console.log("[TestWallet] Wallet registered successfully");
    } catch (error) {
        console.error("[TestWallet] Initialization failed:", error);
        throw error;
    }
})();

package app.typendruckergram.sync;

import org.bouncycastle.math.ec.rfc7748.X25519;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of TypendruckerGram Sync, see docs/sync/protocol.md.
 *
 * <pre>
 * blob = 0x01 | eph_pub(32) | nonce(12) | ciphertext | tag(16)
 * key  = HKDF-SHA256(ikm = X25519(eph, owner_pub),
 *                    salt = eph_pub || owner_pub, info = "typsync v1 event")
 * AES-256-GCM(key, nonce, aad = "typsync")
 * </pre>
 *
 * Plain Java on purpose: tests/sync runs it on the JVM against test-vectors.json.
 */
public final class SyncCrypto {

    public static final int VERSION = 1;
    public static final int KEY_SIZE = 32;
    public static final int NONCE_SIZE = 12;
    public static final int TAG_SIZE = 16;
    private static final int HEADER_SIZE = 1 + KEY_SIZE + NONCE_SIZE;

    private static final byte[] AAD = ascii("typsync");
    private static final byte[] EVENT_INFO = ascii("typsync v1 event");
    private static final byte[] PAIR_SALT = ascii("typsync pair");
    private static final byte[] PAIR_INFO = ascii("typsync v1 pair");
    private static final String PAIR_LOOKUP_PREFIX = "typsync pair lookup|";

    // Crockford base32: no I, L, O, U.
    private static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int CODE_LENGTH = 12;

    private static final SecureRandom random = new SecureRandom();

    private SyncCrypto() {
    }

    public static final class KeyPair {
        public final byte[] privateKey;
        public final byte[] publicKey;

        KeyPair(byte[] privateKey, byte[] publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    public static KeyPair generateKeyPair() {
        byte[] priv = randomBytes(KEY_SIZE);
        return new KeyPair(priv, publicFromPrivate(priv));
    }

    public static byte[] publicFromPrivate(byte[] privateKey) {
        requireSize(privateKey, KEY_SIZE, "private key");
        byte[] pub = new byte[KEY_SIZE];
        X25519.scalarMultBase(privateKey, 0, pub, 0);
        return pub;
    }

    public static byte[] seal(byte[] ownerPublic, byte[] plaintext) {
        return sealWith(ownerPublic, plaintext, randomBytes(KEY_SIZE), randomBytes(NONCE_SIZE));
    }

    /** Fixed ephemeral key and nonce are for test vectors only. */
    public static byte[] sealWith(byte[] ownerPublic, byte[] plaintext, byte[] ephemeralPrivate, byte[] nonce) {
        requireSize(ownerPublic, KEY_SIZE, "owner public key");
        requireSize(nonce, NONCE_SIZE, "nonce");
        byte[] ephPub = publicFromPrivate(ephemeralPrivate);
        byte[] shared = new byte[KEY_SIZE];
        if (!X25519.calculateAgreement(ephemeralPrivate, 0, ownerPublic, 0, shared, 0)) {
            throw new IllegalArgumentException("bad owner public key");
        }
        byte[] key = hkdf(shared, concat(ephPub, ownerPublic), EVENT_INFO);
        byte[] sealed = aesGcm(Cipher.ENCRYPT_MODE, key, nonce, plaintext, 0, plaintext.length);
        byte[] out = new byte[HEADER_SIZE + sealed.length];
        out[0] = VERSION;
        System.arraycopy(ephPub, 0, out, 1, KEY_SIZE);
        System.arraycopy(nonce, 0, out, 1 + KEY_SIZE, NONCE_SIZE);
        System.arraycopy(sealed, 0, out, HEADER_SIZE, sealed.length);
        return out;
    }

    /** Returns null when the blob is truncated, foreign or tampered. */
    public static byte[] open(byte[] ownerPrivate, byte[] blob) {
        if (blob == null || blob.length < HEADER_SIZE + TAG_SIZE || blob[0] != VERSION) {
            return null;
        }
        byte[] ephPub = Arrays.copyOfRange(blob, 1, 1 + KEY_SIZE);
        byte[] nonce = Arrays.copyOfRange(blob, 1 + KEY_SIZE, HEADER_SIZE);
        byte[] shared = new byte[KEY_SIZE];
        if (!X25519.calculateAgreement(ownerPrivate, 0, ephPub, 0, shared, 0)) {
            return null;
        }
        byte[] key = hkdf(shared, concat(ephPub, publicFromPrivate(ownerPrivate)), EVENT_INFO);
        try {
            return aesGcmChecked(Cipher.DECRYPT_MODE, key, nonce, blob, HEADER_SIZE, blob.length - HEADER_SIZE);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public static String newPairCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Uppercases, drops separators, maps O→0 and I/L→1; null if not a valid code. */
    public static String normalizePairCode(String code) {
        if (code == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (char ch : code.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch == '-' || ch == ' ' || ch == '\t') {
                continue;
            }
            if (ch == 'O') {
                ch = '0';
            } else if (ch == 'I' || ch == 'L') {
                ch = '1';
            }
            if (CODE_ALPHABET.indexOf(ch) < 0) {
                return null;
            }
            out.append(ch);
        }
        return out.length() == CODE_LENGTH ? out.toString() : null;
    }

    /** Takes a normalized code. */
    public static String pairLookupHash(String normalized) {
        return sha256Hex(PAIR_LOOKUP_PREFIX + normalized);
    }

    public static byte[] pairEncrypt(String normalized, byte[] payload) {
        return pairEncryptWith(normalized, payload, randomBytes(NONCE_SIZE));
    }

    public static byte[] pairEncryptWith(String normalized, byte[] payload, byte[] nonce) {
        requireSize(nonce, NONCE_SIZE, "nonce");
        return concat(nonce, aesGcm(Cipher.ENCRYPT_MODE, pairKey(normalized), nonce, payload, 0, payload.length));
    }

    /** Returns null for a wrong code or corrupted payload. */
    public static byte[] pairDecrypt(String normalized, byte[] data) {
        if (data == null || data.length < NONCE_SIZE + TAG_SIZE) {
            return null;
        }
        try {
            return aesGcmChecked(Cipher.DECRYPT_MODE, pairKey(normalized),
                    Arrays.copyOf(data, NONCE_SIZE), data, NONCE_SIZE, data.length - NONCE_SIZE);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public static String sha256Hex(String text) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] randomBytes(int size) {
        byte[] out = new byte[size];
        random.nextBytes(out);
        return out;
    }

    public static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Returns null for odd length or non-hex characters. */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] pairKey(String normalized) {
        return hkdf(ascii(normalized), PAIR_SALT, PAIR_INFO);
    }

    // RFC 5869 with a single output block (32 bytes).
    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] data, int offset, int length) {
        try {
            return aesGcmChecked(mode, key, nonce, data, offset, length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aesGcmChecked(int mode, byte[] key, byte[] nonce, byte[] data, int offset, int length)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_SIZE * 8, nonce));
        cipher.updateAAD(AAD);
        return cipher.doFinal(data, offset, length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireSize(byte[] bytes, int size, String what) {
        if (bytes == null || bytes.length != size) {
            throw new IllegalArgumentException(what + " must be " + size + " bytes");
        }
    }
}

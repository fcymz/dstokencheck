package com.ruoyi.dstokencheck.security;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the API key before it is written to disk.
 *
 * <p>Two schemes, picked automatically and distinguished by a prefix so the format is
 * self-describing:
 *
 * <ol>
 *   <li><b>{@code dpapi:}</b> — Windows DPAPI ({@code CryptProtectData}). The encryption key is
 *       managed by Windows and tied to the current <em>user account</em> on this machine, so the
 *       config file cannot be decrypted by another user or after being copied to another computer.
 *       This is the scheme used on Windows, which is where this widget runs.</li>
 *   <li><b>{@code aesgcm:}</b> — AES-256-GCM with a key derived by PBKDF2 from machine and user
 *       identifiers. Used only where DPAPI is unavailable (non-Windows, or if the native call
 *       fails). It stops the key being readable in the file or portable to another machine, but it
 *       is <em>obfuscation, not real secrecy</em>: anyone who can run code as this user could
 *       derive the same key.</li>
 * </ol>
 *
 * <p>Either way the plaintext never touches disk, and a failed decryption returns {@code null}
 * rather than garbage so callers can fall back to asking the user again.
 */
public final class SecretStore {

    private static final String DPAPI_PREFIX = "dpapi:";
    private static final String AES_PREFIX = "aesgcm:";

    private static final int PBKDF2_ITERATIONS = 120000;
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;

    private SecretStore() {
    }

    /** Human-readable name of the scheme that will be used on this machine. */
    public static String activeScheme() {
        return dpapiUsable() ? "Windows DPAPI (按用户+机器加密)" : "AES-256-GCM (密钥由本机信息派生)";
    }

    private static boolean dpapiUsable() {
        return Platform.isWindows();
    }

    /**
     * Encrypts {@code plaintext} into a storable ASCII string.
     *
     * @return the protected value, or an empty string when there is nothing to protect
     */
    public static String protect(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        byte[] raw = plaintext.getBytes(StandardCharsets.UTF_8);

        if (dpapiUsable()) {
            try {
                byte[] encrypted = Crypt32Util.cryptProtectData(raw);
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted);
            } catch (Throwable t) {
                // Fall through to the portable scheme rather than losing the key entirely.
            }
        }
        return encryptWithAes(raw);
    }

    /**
     * Reverses {@link #protect(String)}.
     *
     * @return the plaintext, or {@code null} when the value is missing, corrupt, or was encrypted
     *         by a different user/machine
     */
    public static String unprotect(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            if (stored.startsWith(DPAPI_PREFIX)) {
                byte[] encrypted = Base64.getDecoder().decode(stored.substring(DPAPI_PREFIX.length()));
                return new String(Crypt32Util.cryptUnprotectData(encrypted), StandardCharsets.UTF_8);
            }
            if (stored.startsWith(AES_PREFIX)) {
                return new String(decryptWithAes(stored), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            // Wrong user, wrong machine, or tampered data: treat as "no stored key".
            return null;
        }
        return null;
    }

    /** True when {@code stored} looks like a value this class produced. */
    public static boolean isProtected(String stored) {
        return stored != null && (stored.startsWith(DPAPI_PREFIX) || stored.startsWith(AES_PREFIX));
    }

    // ------------------------------------------------------------- AES-GCM

    private static String encryptWithAes(byte[] raw) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(salt);
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(raw);

            Base64.Encoder b64 = Base64.getEncoder();
            return AES_PREFIX + b64.encodeToString(salt) + ":" + b64.encodeToString(iv) + ":"
                    + b64.encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("无法加密 API Key: " + e.getMessage(), e);
        }
    }

    private static byte[] decryptWithAes(String stored) throws Exception {
        String[] parts = stored.substring(AES_PREFIX.length()).split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("格式不正确");
        }
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] salt = b64.decode(parts[0]);
        byte[] iv = b64.decode(parts[1]);
        byte[] cipherText = b64.decode(parts[2]);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherText);
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(machineSecret(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /** Machine- and user-bound material for the fallback scheme's KDF. */
    private static char[] machineSecret() {
        StringBuilder sb = new StringBuilder();
        sb.append(System.getProperty("user.name", "?")).append('|');
        sb.append(System.getProperty("os.name", "?")).append('|');
        sb.append(System.getProperty("os.arch", "?")).append('|');
        sb.append(System.getProperty("user.home", "?")).append('|');
        try {
            sb.append(InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            sb.append("unknown-host");
        }
        sb.append("|dstokencheck/v1");
        return sb.toString().toCharArray();
    }
}

package com.android.wificalllocal.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Provides PBKDF2 key derivation, challenge-response PIN verification (raw PIN never transmitted),
 * AES-256-GCM group payload encryption, SHA-256 hashing, and exponential rate-limiting.
 */
public class CryptoManager {

    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_ATTEMPTS_BEFORE_LOCK = 5;
    private static final long BASE_LOCKOUT_MS = 60_000L;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, RateLimitState> rateLimits = new ConcurrentHashMap<>();

    public static class RateLimitState {
        public int failedAttempts;
        public int lockoutMultiplier = 1;
        public long lockedUntilMs;
    }

    public String generateSaltBase64() {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public String generateNonceBase64() {
        byte[] nonce = new byte[24];
        secureRandom.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    public byte[] deriveKeyBytes(String pin, String saltBase64) throws GeneralSecurityException {
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return factory.generateSecret(spec).getEncoded();
    }

    public String hashPinWithSalt(String pin, String saltBase64) throws GeneralSecurityException {
        byte[] derived = deriveKeyBytes(pin, saltBase64);
        return Base64.getEncoder().encodeToString(derived);
    }

    public boolean verifyPin(String pin, String saltBase64, String storedHashBase64) {
        if (pin == null || saltBase64 == null || storedHashBase64 == null) return false;
        try {
            String candidate = hashPinWithSalt(pin, saltBase64);
            return MessageDigest.isEqual(
                    candidate.getBytes(StandardCharsets.UTF_8),
                    storedHashBase64.getBytes(StandardCharsets.UTF_8)
            );
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Computes challenge-response proof: HMAC-SHA256(key = PBKDF2(pin, salt), message = nonce + ":" + groupId + ":" + requesterId).
     * Neither side transmits the raw PIN over the network.
     */
    public String computeChallengeResponse(String pin, String saltBase64, String nonceBase64,
                                           String groupId, String requesterId) throws GeneralSecurityException {
        byte[] derivedKey = deriveKeyBytes(pin, saltBase64);
        return computeChallengeResponseFromHash(
                Base64.getEncoder().encodeToString(derivedKey),
                nonceBase64, groupId, requesterId);
    }

    public String computeChallengeResponseFromHash(String pinHashBase64, String nonceBase64,
                                                   String groupId, String requesterId) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(pinHashBase64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        String payload = nonceBase64 + ":" + groupId + ":" + requesterId;
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    public boolean verifyChallengeResponse(String storedPinHashBase64, String nonceBase64,
                                           String groupId, String requesterId, String clientResponse) {
        if (storedPinHashBase64 == null || clientResponse == null) return false;
        try {
            String expected = computeChallengeResponseFromHash(storedPinHashBase64, nonceBase64, groupId, requesterId);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    clientResponse.getBytes(StandardCharsets.UTF_8)
            );
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public long getRemainingLockoutSeconds(String scopeKey) {
        RateLimitState st = rateLimits.get(scopeKey);
        if (st == null) return 0;
        long now = System.currentTimeMillis();
        if (st.lockedUntilMs > now) {
            return (st.lockedUntilMs - now + 999) / 1000;
        }
        return 0;
    }

    public long recordFailedAttempt(String scopeKey) {
        RateLimitState st = rateLimits.computeIfAbsent(scopeKey, k -> new RateLimitState());
        long now = System.currentTimeMillis();
        if (st.lockedUntilMs > now) {
            return (st.lockedUntilMs - now + 999) / 1000;
        }
        st.failedAttempts++;
        if (st.failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCK) {
            long duration = BASE_LOCKOUT_MS * st.lockoutMultiplier;
            st.lockedUntilMs = now + duration;
            st.failedAttempts = 0;
            st.lockoutMultiplier = Math.min(st.lockoutMultiplier * 2, 16);
            return duration / 1000;
        }
        return 0;
    }

    public void resetAttempts(String scopeKey) {
        rateLimits.remove(scopeKey);
    }

    public String encryptAesGcm(String plaintext, byte[] keyBytes) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    public String decryptAesGcm(String cipherBase64, byte[] keyBytes) throws GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(cipherBase64);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new GeneralSecurityException("Malformed ciphertext");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(encrypted);
        return new String(plain, StandardCharsets.UTF_8);
    }

    public static String sha256Hex(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, offset, length);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return sha256Hex(data, 0, data.length);
    }
}

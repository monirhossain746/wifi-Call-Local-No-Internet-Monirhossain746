package com.android.wificalllocal;

import com.android.wificalllocal.security.CryptoManager;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CryptoManagerTest {

    @Test
    public void testSaltedPbkdf2AndChallengeResponseVerification() throws Exception {
        CryptoManager crypto = new CryptoManager();
        String salt1 = crypto.generateSaltBase64();
        String salt2 = crypto.generateSaltBase64();
        assertNotEquals(salt1, salt2);

        String hash1 = crypto.hashPinWithSalt("4829", salt1);
        String hash2 = crypto.hashPinWithSalt("4829", salt2);
        assertNotEquals(hash1, hash2);

        String nonce = crypto.generateNonceBase64();
        String proof = crypto.computeChallengeResponse("4829", salt1, nonce, "grp-1", "peer-a");
        assertTrue(crypto.verifyChallengeResponse(hash1, nonce, "grp-1", "peer-a", proof));
        assertFalse(crypto.verifyChallengeResponse(hash1, nonce, "grp-1", "peer-b", proof));

        String wrongProof = crypto.computeChallengeResponse("0000", salt1, nonce, "grp-1", "peer-a");
        assertFalse(crypto.verifyChallengeResponse(hash1, nonce, "grp-1", "peer-a", wrongProof));
    }

    @Test
    public void testAesGcmEncryptionRoundTrip() throws Exception {
        CryptoManager crypto = new CryptoManager();
        String salt = crypto.generateSaltBase64();
        byte[] key = crypto.deriveKeyBytes("991122", salt);

        String message = "Local private group secret message — বাংলাও কাজ করে!";
        String cipherText = crypto.encryptAesGcm(message, key);
        assertNotEquals(message, cipherText);

        String decrypted = crypto.decryptAesGcm(cipherText, key);
        assertEquals(message, decrypted);
    }

    @Test
    public void testLockoutAfterFiveFailedAttempts() {
        CryptoManager crypto = new CryptoManager();
        String scope = "grp-lock:peer-x";
        assertEquals(0L, crypto.getRemainingLockoutSeconds(scope));

        for (int i = 0; i < 4; i++) {
            assertEquals(0L, crypto.recordFailedAttempt(scope));
        }
        long lockoutSec = crypto.recordFailedAttempt(scope);
        assertTrue(lockoutSec >= 60L);
        assertTrue(crypto.getRemainingLockoutSeconds(scope) > 0L);

        crypto.resetAttempts(scope);
        assertEquals(0L, crypto.getRemainingLockoutSeconds(scope));
    }

    @Test
    public void testSha256HexKnownVector() {
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoManager.sha256Hex(input)
        );
    }
}

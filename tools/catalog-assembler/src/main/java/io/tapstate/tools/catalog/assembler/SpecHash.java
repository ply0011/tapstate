package io.tapstate.tools.catalog.assembler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The fingerprint a catalog row keeps of the specification it was generated from — the first 16 hex
 * characters of the content's SHA-256, enough to detect any upstream change.
 *
 * <p>One implementation, because two surfaces compare against the same recorded value: generation
 * stamps it, and a drift scan asks whether upstream still matches it. A second copy would answer
 * "everything changed" the day the two fell out of step, and nothing would say which was wrong.
 */
final class SpecHash {

    private SpecHash() {
    }

    static String of(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

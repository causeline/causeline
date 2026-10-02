// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** The secret that unlocks the {@code /causeline} API. Configured, or random per application start. */
public final class AccessToken {

    private final String value;
    private final boolean generated;

    private AccessToken(String value, boolean generated) {
        this.value = value;
        this.generated = generated;
    }

    public static AccessToken of(String configured) {
        if (configured != null && !configured.isBlank()) {
            return new AccessToken(configured, false);
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return new AccessToken(HexFormat.of().formatHex(random), true);
    }

    /** Constant-time comparison, so response timing reveals nothing about the token. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    public boolean generated() {
        return generated;
    }

    String value() {
        return value;
    }
}

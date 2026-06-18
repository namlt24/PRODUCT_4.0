package com.telecom.bccs.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA public key (from the JWT_PUBLIC_KEY env var) once at startup and keeps it
 * in memory so asymmetric JWT signatures can be verified locally at the gateway without a
 * network round-trip to the 3rd-party auth provider on every request.
 */
@Component
public class JwtPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtPublicKeyProvider.class);

    private final String base64Key;
    private volatile PublicKey publicKey;

    public JwtPublicKeyProvider(@Value("${gateway.auth.jwt-public-key:}") String base64Key) {
        this.base64Key = base64Key;
    }

    @PostConstruct
    void init() {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("No JWT public key configured. Gateway will rely on remote introspection only.");
            return;
        }
        try {
            // Accept either a raw base64 DER or a PEM block with header/footer lines
            String cleaned = base64Key
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            this.publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            log.info("Loaded RSA public key for in-memory JWT validation");
        } catch (Exception e) {
            log.error("Failed to parse JWT public key, falling back to remote introspection", e);
        }
    }

    public boolean isLocalValidationEnabled() {
        return publicKey != null;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }
}

package com.telecom.bccs.integration.service;

import com.telecom.bccs.integration.client.ThirdPartyAuthClient;
import com.telecom.bccs.integration.model.TokenValidationResult;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Validates JWTs. Prefers fast local asymmetric verification with the cached RSA public key;
 * falls back to remote introspection at the 3rd-party provider when no key is configured or
 * when the token signature cannot be verified locally (e.g. opaque/reference tokens).
 */
@Service
public class TokenValidationService {

    private static final Logger log = LoggerFactory.getLogger(TokenValidationService.class);

    private final ThirdPartyAuthClient thirdPartyAuthClient;
    private final String base64PublicKey;
    private PublicKey publicKey;

    public TokenValidationService(ThirdPartyAuthClient thirdPartyAuthClient,
                                  @Value("${integration.auth.jwt-public-key:}") String base64PublicKey) {
        this.thirdPartyAuthClient = thirdPartyAuthClient;
        this.base64PublicKey = base64PublicKey;
    }

    @PostConstruct
    void init() {
        if (base64PublicKey == null || base64PublicKey.isBlank()) {
            log.warn("No JWT public key set; will rely on remote introspection only");
            return;
        }
        try {
            String cleaned = base64PublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            log.info("Loaded RSA public key for local JWT validation");
        } catch (Exception e) {
            log.error("Could not parse JWT public key: {}", e.getMessage());
        }
    }

    public TokenValidationResult validate(String token) {
        if (publicKey != null) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .clockSkewSeconds(30)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                String clientId = claims.get("client_id", String.class);
                if (clientId == null) {
                    clientId = claims.get("azp", String.class);
                }
                Long exp = claims.getExpiration() != null ? claims.getExpiration().toInstant().getEpochSecond() : null;
                return TokenValidationResult.active(claims.getSubject(),
                        clientId != null ? clientId : "unknown", exp);
            } catch (JwtException e) {
                log.debug("Local JWT validation failed, falling back to introspection: {}", e.getMessage());
                // fall through to remote
            }
        }
        return thirdPartyAuthClient.introspect(token);
    }
}

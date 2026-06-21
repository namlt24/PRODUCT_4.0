package com.telecom.bccs.integration.service;

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
 * Xác thực JWT cho NGƯỜI DÙNG NỘI BỘ (FE quản trị ↔ BE). Đối tác bên thứ 3 KHÔNG còn dùng
 * service này — họ xác thực bằng API key tại gateway.
 *
 * <p>Verify cục bộ bằng RSA public key (lấy từ IdP nội bộ/SSO). Token không hợp lệ → inactive;
 * KHÔNG còn fallback introspection sang nhà cung cấp đối tác.
 *
 * <p>Định hướng production: đứng trước/tích hợp một IdP có sẵn (Keycloak/AD/LDAP/SSO) để phát và
 * xoay khóa, và bổ sung RBAC theo vai trò (vd CATALOG_ADMIN) cho đường ghi.
 */
@Service
public class TokenValidationService {

    private static final Logger log = LoggerFactory.getLogger(TokenValidationService.class);

    private final String base64PublicKey;
    private PublicKey publicKey;

    public TokenValidationService(@Value("${integration.auth.jwt-public-key:}") String base64PublicKey) {
        this.base64PublicKey = base64PublicKey;
    }

    @PostConstruct
    void init() {
        if (base64PublicKey == null || base64PublicKey.isBlank()) {
            log.warn("No JWT public key set; internal token validation will reject all tokens until configured");
            return;
        }
        try {
            String cleaned = base64PublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            log.info("Loaded RSA public key for internal JWT validation");
        } catch (Exception e) {
            log.error("Could not parse JWT public key: {}", e.getMessage());
        }
    }

    public TokenValidationResult validate(String token) {
        if (publicKey == null) {
            return TokenValidationResult.inactive("no_validation_key_configured");
        }
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
            log.debug("Internal JWT validation failed: {}", e.getMessage());
            return TokenValidationResult.inactive("invalid_token");
        }
    }
}

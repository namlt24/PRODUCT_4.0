package com.telecom.bccs.integration.controller;

import com.telecom.bccs.integration.model.TokenValidationResult;
import com.telecom.bccs.integration.service.TokenValidationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint xác thực token cho NGƯỜI DÙNG NỘI BỘ (FE quản trị ↔ BE). Trả về body kiểu
 * OAuth2-introspection để bên gọi (admin gateway/BFF) cho phép/từ chối request.
 * Đối tác bên thứ 3 không dùng endpoint này (họ xác thực bằng API key tại gateway).
 */
@RestController
@RequestMapping("/api/v1/integration")
public class AuthValidationController {

    private final TokenValidationService validationService;

    public AuthValidationController(TokenValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping("/validate")
    public ResponseEntity<TokenValidationResult> validate(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.ok(TokenValidationResult.inactive("missing_bearer_token"));
        }
        String token = authHeader.substring(7);
        TokenValidationResult result = validationService.validate(token);
        return ResponseEntity.ok(result);
    }
}

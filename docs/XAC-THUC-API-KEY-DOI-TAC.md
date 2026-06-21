# Xác thực đối tác bằng API Key (tại Gateway)

> Đối tác bên thứ 3 (đường ĐỌC catalog) xác thực bằng cặp **(X-Client-Id, X-Api-Key)** ngay tại
> `api-gateway`, **không gọi integration-service**. Tài liệu này là nguồn tham chiếu chính cho mô hình
> auth đối tác (thay cho mô hình JWT trước đây).

## 1. Hai miền xác thực tách biệt

| Miền | Ai | Cơ chế | Ở đâu |
|---|---|---|---|
| **Đối tác (READ, external)** | Hệ thống bên thứ 3 | `X-Client-Id` + `X-Api-Key` → so khớp `api_key_hash` | `api-gateway` (`ApiKeyAuthFilter`) |
| **Nội bộ (WRITE, internal)** | FE quản trị ↔ BE | JWT người dùng nội bộ + RBAC | `integration-service` (định hướng tích hợp IdP) |

## 2. Luồng xác thực đối tác

```
Đối tác gửi:  X-Client-Id: PARTNER_A
              X-Api-Key:   <API_KEY thô>
                  │
        ApiKeyAuthFilter (HIGHEST_PRECEDENCE+10, sau trace, trước route)
                  │  bỏ qua permit-all (health/metrics/fallback)
                  │  thiếu client-id/api-key → 401
                  ▼
        PartnerRateLimitConfigService.authenticate(clientId, apiKey)
                  │  tra partner theo clientId trong SNAPSHOT in-memory (không chạm DB)
                  │  SHA-256(apiKey) == api_key_hash ?  (so sánh HẰNG THỜI GIAN)
                  │  đối tác lạ / không có key / SUSPENDED → false
                  ▼ true
        chuẩn hóa X-Client-Id (tin cậy) + XÓA X-Api-Key  →  rate-limit  →  catalog
```

## 3. Bảng `partner` (cột xác thực)

```sql
code          VARCHAR(64)  -- = client_id (định danh)
status        VARCHAR(16)  -- ACTIVE / SUSPENDED
tier_code     VARCHAR(32)  -- gói → hạn mức mặc định
api_key_hash  CHAR(64)     -- SHA-256 hex của API_KEY (KHÔNG lưu key thô)
```
- **Chỉ lưu hash**, không lưu key thô → DB leak cũng không lộ key.
- Cùng bảng/snapshot phục vụ cả **xác thực** lẫn **rate-limit** → một lần nạp, không thêm dependency.

## 4. Vì sao thiết kế thế này

- **Sub-ms, không gọi mạng**: tra hash trong `Map` in-memory; DB chỉ đọc ở tác vụ `@Scheduled` nền (15s).
- **Chống giả mạo**: API key chứng minh quyền sở hữu `client_id`; sau xác thực `X-Client-Id` là tin cậy → khóa rate-limit không bị mượn.
- **Chống timing attack**: so khớp hash bằng `MessageDigest.isEqual` (hằng thời gian).
- **Không rò secret**: `X-Api-Key` bị xóa khỏi request trước khi forward downstream.
- **Thu hồi tức thì**: xóa/đổi `api_key_hash` trong DB → sau ≤ refresh (15s) là chặn (ưu thế lớn so với JWT khó thu hồi).
- **Fail-closed (đúng cho auth)**: DB config sập lúc khởi động → snapshot rỗng → đối tác 401. Auth **không** fail-open như rate-limit. Vì vậy gateway `depends_on mariadb healthy`.

## 5. Vận hành (admin bằng SQL)

```sql
-- Cấp/đổi key cho đối tác (lưu HASH, không lưu key thô). Sinh hash: echo -n '<key>' | sha256sum
UPDATE partner SET api_key_hash = '<sha256hex>' WHERE code = 'PARTNER_A';

-- Treo đối tác (chặn cả auth lẫn traffic)
UPDATE partner SET status = 'SUSPENDED' WHERE code = 'PARTNER_X';

-- Thu hồi key (đối tác sẽ 401 sau ≤ refresh-ms)
UPDATE partner SET api_key_hash = NULL WHERE code = 'PARTNER_B';
```

## 6. Cách test

```bash
# Hợp lệ → 200
curl -i -H "X-Client-Id: PARTNER_A" -H "X-Api-Key: bccs_ak_partnerA_demo" \
  http://localhost:8080/api/v1/products/<id>

# Sai key → 401
curl -i -H "X-Client-Id: PARTNER_A" -H "X-Api-Key: sai" \
  http://localhost:8080/api/v1/products/<id>

# Thiếu header → 401 ; đối tác SUSPENDED (PARTNER_X) → 401
```
Key demo (định nghĩa trong `product-catalog-service/.../db/schema.sql`):
`PARTNER_A=bccs_ak_partnerA_demo` · `PARTNER_B=bccs_ak_partnerB_demo` · `PARTNER_GOLD=bccs_ak_gold_demo` · `PARTNER_X=bccs_ak_x_demo` (treo).

## 7. Còn nợ cho production

- **HMAC-with-pepper** thay SHA-256 trần (nếu lo DB leak): `HMAC(serverPepper, apiKey)`, pepper giữ trong Vault.
- **Rotation**: cho phép 2 key active đồng thời + `valid_to` để xoay không downtime.
- **Thu hồi tức thì**: thêm denylist/pub-sub thay vì chờ refresh 15s.
- **Chỉ TLS**: API key là bearer secret — bắt buộc HTTPS ở mép ngoài.
- **Rate-limit theo IP cho request chưa auth** (đã có fallback `ip:<addr>` ở KeyResolver) để chống dò key.

## 8. Đã thay đổi gì so với mô hình JWT cũ

- ❌ Bỏ `AuthenticationFilter` (JWT) + `JwtPublicKeyProvider` ở gateway.
- ❌ Bỏ introspection đối tác trong `integration-service` (`ThirdPartyAuthClient`).
- ✅ Thêm `ApiKeyAuthFilter` + `authenticate()` trong `PartnerRateLimitConfigService` + cột `api_key_hash`.
- ✅ `integration-service` đổi vai trò → auth **nội bộ** FE↔BE.

-- Shared schema (bccs_catalog) used by catalog (read) and management (write) services.
-- Apply once to the MariaDB instance. ddl-auto is set to "validate", not "update".

CREATE TABLE IF NOT EXISTS product (
    id           VARCHAR(64)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    category     VARCHAR(64),
    status       VARCHAR(16)  NOT NULL,
    description  VARCHAR(2000),
    updated_at   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_code (code),
    KEY idx_product_category (category),
    KEY idx_product_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS offer (
    id            VARCHAR(64)   NOT NULL,
    product_id    VARCHAR(64)   NOT NULL,
    code          VARCHAR(64)   NOT NULL,
    name          VARCHAR(255),
    price         DECIMAL(18,2) NOT NULL,
    currency      VARCHAR(8)    DEFAULT 'VND',
    billing_cycle VARCHAR(16),
    status        VARCHAR(16),
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_code (code),
    KEY idx_offer_product (product_id),
    CONSTRAINT fk_offer_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- DYNAMIC RATE LIMITING theo đối tác (api-gateway nạp động từ các bảng này)
-- ============================================================================

-- Hạn mức mặc định theo GÓI dịch vụ (tier). Đối tác kế thừa tier nếu không có override.
CREATE TABLE IF NOT EXISTS rate_limit_tier (
    tier_code        VARCHAR(32)  NOT NULL,                 -- BRONZE/SILVER/GOLD/INTERNAL
    replenish_rate   INT          NOT NULL,                 -- TPS ổn định (tokens/giây)
    burst_capacity   INT          NOT NULL,                 -- sức chứa burst (đỉnh ngắn hạn)
    requested_tokens INT          NOT NULL DEFAULT 1,        -- token tiêu thụ mỗi request
    description      VARCHAR(255),
    PRIMARY KEY (tier_code),
    CONSTRAINT chk_tier_rate CHECK (replenish_rate >= 0 AND burst_capacity >= replenish_rate)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Đối tác bên thứ 3. code = client_id trong JWT.
CREATE TABLE IF NOT EXISTS partner (
    id          VARCHAR(64)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,                       -- = client_id (claim JWT)
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE/SUSPENDED
    tier_code   VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_code (code),
    KEY idx_partner_status (status),
    CONSTRAINT fk_partner_tier FOREIGN KEY (tier_code) REFERENCES rate_limit_tier (tier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Override hạn mức theo từng đối tác, có thể theo từng nhóm API (api_scope = routeId) và có thời hạn.
CREATE TABLE IF NOT EXISTS partner_rate_limit (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    partner_code     VARCHAR(64)  NOT NULL,                  -- = partner.code (client_id)
    api_scope        VARCHAR(64)  NOT NULL DEFAULT 'DEFAULT',-- 'DEFAULT' | routeId (vd 'product-catalog')
    replenish_rate   INT          NOT NULL,
    burst_capacity   INT          NOT NULL,
    requested_tokens INT          NOT NULL DEFAULT 1,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    valid_from       TIMESTAMP    NULL,                      -- hiệu lực từ (NULL = ngay)
    valid_to         TIMESTAMP    NULL,                      -- hết hiệu lực (NULL = vô hạn)
    updated_at       TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64),                            -- audit ai đổi
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_scope (partner_code, api_scope),   -- mỗi (đối tác, scope) 1 bản
    KEY idx_prl_partner (partner_code),
    KEY idx_prl_enabled (enabled),
    CONSTRAINT chk_prl_rate CHECK (replenish_rate >= 0 AND burst_capacity >= replenish_rate)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -------- Seed dữ liệu mẫu --------
INSERT INTO rate_limit_tier (tier_code, replenish_rate, burst_capacity, requested_tokens, description) VALUES
    ('BRONZE',   20,   40,   1, 'Gói cơ bản'),
    ('SILVER',   100,  200,  1, 'Gói tiêu chuẩn'),
    ('GOLD',     500,  1000, 1, 'Gói cao cấp'),
    ('INTERNAL', 2000, 4000, 1, 'Hệ thống nội bộ')
ON DUPLICATE KEY UPDATE replenish_rate=VALUES(replenish_rate), burst_capacity=VALUES(burst_capacity);

INSERT INTO partner (id, code, name, status, tier_code) VALUES
    ('p-001', 'PARTNER_A',    'Đối tác A (demo JWT)', 'ACTIVE',    'SILVER'),
    ('p-002', 'PARTNER_B',    'Đối tác B',            'ACTIVE',    'BRONZE'),
    ('p-003', 'PARTNER_GOLD', 'Đối tác VIP',          'ACTIVE',    'GOLD'),
    ('p-004', 'PARTNER_X',    'Đối tác bị treo',      'SUSPENDED', 'BRONZE')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- PARTNER_A: đọc catalog được nâng riêng (override theo API), ghi quản lý hạ thấp
INSERT INTO partner_rate_limit (partner_code, api_scope, replenish_rate, burst_capacity, updated_by) VALUES
    ('PARTNER_A', 'product-catalog',    300, 600, 'seed'),
    ('PARTNER_A', 'product-management', 10,  20,  'seed'),
    ('PARTNER_B', 'product-management', 5,   10,  'seed')
ON DUPLICATE KEY UPDATE replenish_rate=VALUES(replenish_rate), burst_capacity=VALUES(burst_capacity);

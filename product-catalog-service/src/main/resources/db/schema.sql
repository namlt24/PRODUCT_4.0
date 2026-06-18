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

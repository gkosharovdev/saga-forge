CREATE TABLE IF NOT EXISTS jdbc_order (
    order_id  TEXT PRIMARY KEY,
    in_stock  BOOLEAN NOT NULL,
    status    TEXT NOT NULL,
    version   BIGINT
);
CREATE TABLE IF NOT EXISTS jdbc_invoice (
    invoice_id  UUID PRIMARY KEY
);

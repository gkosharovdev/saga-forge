CREATE TABLE IF NOT EXISTS orders (
    order_id  TEXT PRIMARY KEY,
    in_stock  BOOLEAN NOT NULL,
    status    TEXT NOT NULL,
    version   BIGINT
);

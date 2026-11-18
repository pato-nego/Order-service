CREATE TABLE orders.orders (
    id UUID PRIMARY KEY,
    id_account VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    total NUMERIC(19, 2) NOT NULL
);

CREATE INDEX idx_orders_id_account ON orders.orders (id_account);

CREATE TABLE orders.order_items (
    id UUID PRIMARY KEY,
    id_order UUID NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    id_product UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) NOT NULL,
    total NUMERIC(19, 2) NOT NULL
);

CREATE INDEX idx_order_items_id_order ON orders.order_items (id_order);

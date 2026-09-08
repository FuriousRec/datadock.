CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    sha256 CHAR(64) NOT NULL UNIQUE,
    row_count INTEGER NOT NULL CHECK (row_count BETWEEN 1 AND 10000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sale (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    sold_at DATE NOT NULL,
    sku VARCHAR(80) NOT NULL,
    category VARCHAR(80) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    unit_price NUMERIC(8, 2) NOT NULL CHECK (unit_price >= 0)
);
CREATE INDEX sale_sold_at_idx ON sale(sold_at);
CREATE INDEX sale_import_id_idx ON sale(import_id);

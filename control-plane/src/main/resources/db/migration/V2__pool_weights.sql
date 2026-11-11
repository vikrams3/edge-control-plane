-- Current traffic split per backend pool, so a control plane restart keeps it.
CREATE TABLE pool_weights (
    pool        VARCHAR(64) PRIMARY KEY,
    weights     JSONB       NOT NULL,     -- backend -> percent
    updated_at  TIMESTAMPTZ NOT NULL
);

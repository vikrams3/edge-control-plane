-- Every rollout: the candidate route table, how the canary answered, and the outcome.
CREATE TABLE rollouts (
    version         BIGINT PRIMARY KEY,           -- xDS snapshot version pushed to the canary
    finished_at     TIMESTAMPTZ      NOT NULL,
    routes          JSONB            NOT NULL,    -- hostname -> backend
    outcome         VARCHAR(16)      NOT NULL,    -- promoted | rolled_back
    canary_ack      VARCHAR(16)      NOT NULL,    -- acked | nacked | timeout | no_nodes
    probe_requests  INTEGER          NOT NULL,
    probe_failures  INTEGER          NOT NULL,
    error_rate      DOUBLE PRECISION NOT NULL,
    reverted_from   BIGINT REFERENCES rollouts (version)
);

-- Startup looks up the newest promoted rollout to restore.
CREATE INDEX rollouts_promoted ON rollouts (version DESC) WHERE outcome = 'promoted';

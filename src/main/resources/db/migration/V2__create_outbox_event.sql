CREATE TABLE outbox_event (
    id           UUID                     NOT NULL,
    aggregate_id UUID                     NOT NULL,
    event_type   VARCHAR(64)              NOT NULL,
    payload      VARCHAR(4000)            NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);

-- Relay: unpublished events in creation order.
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (published_at, created_at);
CREATE INDEX ix_outbox_event_aggregate ON outbox_event (aggregate_id, created_at);

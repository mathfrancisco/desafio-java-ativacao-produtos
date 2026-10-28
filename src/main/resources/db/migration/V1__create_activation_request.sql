CREATE TABLE activation_request (
    id              UUID                     NOT NULL,
    external_id     VARCHAR(64)              NOT NULL,
    client_id       VARCHAR(128)             NOT NULL,
    customer_id     VARCHAR(64)              NOT NULL,
    product_code    VARCHAR(64)              NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    attempts        INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until    TIMESTAMP WITH TIME ZONE,
    failure_reason  VARCHAR(255),
    version         BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_activation_request PRIMARY KEY (id),
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Worker: due pending requests and processing requests with an expired lease.
CREATE INDEX ix_activation_request_due ON activation_request (status, next_attempt_at);

-- Client-scoped listing, filtered by status or customer and ordered by creation date.
CREATE INDEX ix_activation_request_client_status ON activation_request (client_id, status, created_at);
CREATE INDEX ix_activation_request_client_customer ON activation_request (client_id, customer_id, created_at);

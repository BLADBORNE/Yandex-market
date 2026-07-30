CREATE SCHEMA IF NOT EXISTS market;

CREATE TABLE IF NOT EXISTS market.product
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(100)   NOT NULL UNIQUE,
    description VARCHAR(255)   NOT NULL,
    img_path    VARCHAR(100)   NOT NULL UNIQUE,
    price       NUMERIC(19, 2) NOT NULL CHECK (price > 0)
);

ALTER TABLE market.product
    ALTER COLUMN price TYPE NUMERIC(19, 2);

ALTER TABLE market.product
    DROP CONSTRAINT IF EXISTS product_price_check;

ALTER TABLE market.product
    ADD CONSTRAINT product_price_check CHECK (price > 0);

CREATE TABLE IF NOT EXISTS market.user_account
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username           VARCHAR(64)  NOT NULL UNIQUE,
    password_hash      VARCHAR(100) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    payment_account_id UUID         NOT NULL UNIQUE
);

INSERT INTO market.user_account (username, password_hash, enabled, payment_account_id)
VALUES ('alice', '$2y$12$LKqRooEybEI0ZunI0pBun.10oAEeolTylD31ab2w6/9ZYn8Eh8t.6',
        TRUE, '85a65fde-0492-4dcf-b1f4-dbc8f901ae72'),
       ('bob', '$2y$12$eHjoKLazpn0Lg7/MXKQgEuRAldpLvwGn/utFRWA6UYMMg1f8eT/3e',
        TRUE, '0c9f6374-c617-4f97-bd90-3de6f7fdfac5')
ON CONFLICT DO NOTHING;

CREATE UNIQUE INDEX IF NOT EXISTS user_account_id_payment_account_uq
    ON market.user_account (id, payment_account_id);

CREATE TABLE IF NOT EXISTS market.basket
(
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT      NOT NULL,
    status  VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'CHECKOUT', 'CLOSED'))
);

-- Sprint 7 volumes do not have an owner. Preserve their data under the seeded Alice account.
ALTER TABLE market.basket
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

UPDATE market.basket
SET user_id = (SELECT id FROM market.user_account WHERE username = 'alice')
WHERE user_id IS NULL;

ALTER TABLE market.basket
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE market.basket
    DROP CONSTRAINT IF EXISTS basket_status_check;

ALTER TABLE market.basket
    ADD CONSTRAINT basket_status_check
        CHECK (status IN ('ACTIVE', 'CHECKOUT', 'CLOSED'));

ALTER TABLE market.basket
    DROP CONSTRAINT IF EXISTS basket_user_account_fk;

ALTER TABLE market.basket
    ADD CONSTRAINT basket_user_account_fk
        FOREIGN KEY (user_id) REFERENCES market.user_account (id);

DROP INDEX IF EXISTS market.basket_single_active_idx;
DROP INDEX IF EXISTS market.basket_single_active_per_user_idx;

WITH ranked_open_baskets AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY id DESC) AS position
    FROM market.basket
    WHERE status IN ('ACTIVE', 'CHECKOUT')
)
UPDATE market.basket b
SET status = 'CLOSED'
FROM ranked_open_baskets ranked
WHERE b.id = ranked.id
  AND ranked.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS basket_single_open_per_user_idx
    ON market.basket (user_id)
    WHERE status IN ('ACTIVE', 'CHECKOUT');

CREATE UNIQUE INDEX IF NOT EXISTS basket_id_user_id_uq
    ON market.basket (id, user_id);

CREATE TABLE IF NOT EXISTS market."order"
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    basket_id BIGINT        NOT NULL,
    user_id   BIGINT        NOT NULL,
    sum       NUMERIC(19, 2) NOT NULL CHECK (sum >= 0),
    payment_account_id UUID,
    payment_request_id UUID,
    payment_attempt_id UUID,
    payment_attempt_started_at TIMESTAMPTZ,
    payment_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    status    VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT order_pending_payment_identity_check CHECK (
        status = 'COMPLETED'
        OR (payment_account_id IS NOT NULL AND payment_request_id IS NOT NULL)
    ),
    CONSTRAINT order_payment_attempt_state_check CHECK (
        (
            status = 'COMPLETED'
            AND payment_attempt_id IS NULL
            AND payment_attempt_started_at IS NULL
        )
        OR (
            status = 'PENDING'
            AND (
                (
                    payment_attempt_id IS NULL
                    AND payment_attempt_started_at IS NULL
                )
                OR (
                    payment_attempt_id IS NOT NULL
                    AND payment_attempt_started_at IS NOT NULL
                )
            )
        )
    )
);

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS payment_account_id UUID;

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS payment_request_id UUID;

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS payment_attempt_id UUID;

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS payment_attempt_started_at TIMESTAMPTZ;

ALTER TABLE market."order"
    ADD COLUMN IF NOT EXISTS payment_attempted BOOLEAN NOT NULL DEFAULT FALSE;

-- An already existing pending order may have reached payment-service before an
-- application restart. Treat it conservatively as attempted so a later reject
-- cannot discard an intent whose successful response might have been lost.
UPDATE market."order"
SET payment_attempted = TRUE
WHERE status = 'PENDING';

UPDATE market."order" o
SET user_id = b.user_id
FROM market.basket b
WHERE o.basket_id = b.id
  AND o.user_id IS NULL;

UPDATE market."order"
SET status = 'COMPLETED'
WHERE status IS NULL;

ALTER TABLE market."order"
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE market."order"
    ALTER COLUMN status SET DEFAULT 'COMPLETED';

ALTER TABLE market."order"
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_status_check;

ALTER TABLE market."order"
    ADD CONSTRAINT order_status_check
        CHECK (status IN ('PENDING', 'COMPLETED'));

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_pending_payment_identity_check;

ALTER TABLE market."order"
    ADD CONSTRAINT order_pending_payment_identity_check
        CHECK (
            status = 'COMPLETED'
            OR (payment_account_id IS NOT NULL AND payment_request_id IS NOT NULL)
        );

UPDATE market."order"
SET payment_attempt_id = NULL,
    payment_attempt_started_at = NULL
WHERE status = 'COMPLETED';

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_payment_attempt_pair_check;

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_payment_attempt_state_check;

ALTER TABLE market."order"
    ADD CONSTRAINT order_payment_attempt_state_check
        CHECK (
            (
                status = 'COMPLETED'
                AND payment_attempt_id IS NULL
                AND payment_attempt_started_at IS NULL
            )
            OR (
                status = 'PENDING'
                AND (
                    (
                        payment_attempt_id IS NULL
                        AND payment_attempt_started_at IS NULL
                    )
                    OR (
                        payment_attempt_id IS NOT NULL
                        AND payment_attempt_started_at IS NOT NULL
                    )
                )
            )
        );

-- The composite owner FK below supersedes Sprint 7's basket-only FK.
ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_basket_id_fkey;

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_user_account_fk;

ALTER TABLE market."order"
    ADD CONSTRAINT order_user_account_fk
        FOREIGN KEY (user_id) REFERENCES market.user_account (id);

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_payment_owner_fk;

ALTER TABLE market."order"
    ADD CONSTRAINT order_payment_owner_fk
        FOREIGN KEY (user_id, payment_account_id)
        REFERENCES market.user_account (id, payment_account_id);

ALTER TABLE market."order"
    DROP CONSTRAINT IF EXISTS order_basket_owner_fk;

ALTER TABLE market."order"
    ADD CONSTRAINT order_basket_owner_fk
        FOREIGN KEY (basket_id, user_id) REFERENCES market.basket (id, user_id);

CREATE UNIQUE INDEX IF NOT EXISTS order_single_basket_idx
    ON market."order" (basket_id);

CREATE INDEX IF NOT EXISTS order_user_id_idx
    ON market."order" (user_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS order_single_pending_per_user_idx
    ON market."order" (user_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS order_payment_request_id_uq
    ON market."order" (payment_request_id)
    WHERE payment_request_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS order_payment_attempt_id_uq
    ON market."order" (payment_attempt_id)
    WHERE payment_attempt_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS market.basket_product
(
    product_id BIGINT  NOT NULL REFERENCES market.product (id),
    basket_id  BIGINT  NOT NULL REFERENCES market.basket (id),
    count      INTEGER NOT NULL CHECK (count > 0),
    PRIMARY KEY (product_id, basket_id)
);

CREATE TABLE IF NOT EXISTS market.order_item
(
    order_id   BIGINT         NOT NULL REFERENCES market."order" (id) ON DELETE CASCADE,
    product_id BIGINT         NOT NULL,
    title      VARCHAR(100)   NOT NULL,
    price      NUMERIC(19, 2) NOT NULL CHECK (price >= 0),
    count      INTEGER        NOT NULL CHECK (count > 0),
    PRIMARY KEY (order_id, product_id)
);

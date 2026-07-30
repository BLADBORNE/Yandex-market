CREATE SCHEMA IF NOT EXISTS market;

CREATE TABLE IF NOT EXISTS market.product
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(100)   NOT NULL UNIQUE,
    description VARCHAR(255)   NOT NULL,
    img_path    VARCHAR(100)   NOT NULL UNIQUE,
    price       NUMERIC(19, 2) NOT NULL CHECK (price >= 0)
);

ALTER TABLE market.product
    ALTER COLUMN price TYPE NUMERIC(19, 2);

CREATE TABLE IF NOT EXISTS market.basket
(
    id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED'))
);

WITH ranked_active_baskets AS (
    SELECT id,
           ROW_NUMBER() OVER (ORDER BY id DESC) AS position
    FROM market.basket
    WHERE status = 'ACTIVE'
)
UPDATE market.basket b
SET status = 'CLOSED'
FROM ranked_active_baskets ranked
WHERE b.id = ranked.id
  AND ranked.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS basket_single_active_idx
    ON market.basket (status)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS market."order"
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    basket_id BIGINT        NOT NULL REFERENCES market.basket (id),
    sum       NUMERIC(19, 2) NOT NULL CHECK (sum >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS order_single_basket_idx
    ON market."order" (basket_id);

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

--liquibase formatted sql

--changeset BLADBORNE:001_create_app_scheme
CREATE SCHEMA IF NOT EXISTS market;

--changeset BLADBORNE:002_create_product
CREATE TABLE IF NOT EXISTS market.product
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    title       VARCHAR(100)                        NOT NULL,
    description VARCHAR(255)                        NOT NULL,
    img_path    VARCHAR(100)                        NOT NULL,
    price       NUMERIC(10, 2)                      NOT NULL
);

--changeset BLADBORNE:003_create_basket
CREATE TABLE IF NOT EXISTS market.basket
(
    id     BIGINT GENERATED ALWAYS AS IDENTITY,
    status VARCHAR(20) NOT NULL
);

--changeset BLADBORNE:004_create_order
CREATE TABLE IF NOT EXISTS market.order
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    basket_id BIGINT                              NOT NULL,
    sum       NUMERIC(19, 2)                      NOT NULL
);

--changeset BLADBORNE:005_create_basket_product
CREATE TABLE IF NOT EXISTS market.basket_product
(
    product_id BIGINT  NOT NULL,
    basket_id  BIGINT  NOT NULL,
    count      INTEGER NOT NULL
);

--changeset BLADBORNE:006_add_product_constraints
ALTER TABLE market.product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);

ALTER TABLE market.product
    ADD CONSTRAINT product_title_unique UNIQUE (title);

ALTER TABLE market.product
    ADD CONSTRAINT product_img_path_unique UNIQUE (img_path);

--changeset BLADBORNE:007_add_basket_constraints
ALTER TABLE market.basket
    ADD CONSTRAINT basket_pkey PRIMARY KEY (id);

ALTER TABLE market.basket
    ADD CONSTRAINT basket_status_check CHECK (status IN ('ACTIVE', 'CLOSED'));

--changeset BLADBORNE:008_add_order_constraints
ALTER TABLE market.order
    ADD CONSTRAINT order_pkey PRIMARY KEY (id);

ALTER TABLE market.order
    ADD CONSTRAINT order_basket_id_fk FOREIGN KEY (basket_id)
        REFERENCES market.basket (id);

--changeset BLADBORNE:009_add_basket_product_constraints
ALTER TABLE market.basket_product
    ADD CONSTRAINT basket_product_pkey PRIMARY KEY (product_id, basket_id);

ALTER TABLE market.basket_product
    ADD CONSTRAINT basket_product_product_id_fk FOREIGN KEY (product_id)
        REFERENCES market.product (id);

ALTER TABLE market.basket_product
    ADD CONSTRAINT basket_product_basket_id_fk FOREIGN KEY (basket_id)
        REFERENCES market.basket (id);

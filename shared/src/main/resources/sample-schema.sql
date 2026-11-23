-- Sample data that published APIs can query (everything in the target schema is queryable).
-- Replace these tables with your own, or point the services at your own database instead.
CREATE TABLE IF NOT EXISTS ACCOUNTS (
    ID        INT PRIMARY KEY,
    OWNER     VARCHAR(100)   NOT NULL,
    CITY      VARCHAR(60)    NOT NULL,
    TYPE      VARCHAR(20)    NOT NULL,
    BALANCE   DECIMAL(15, 2) NOT NULL,
    ACTIVE    BOOLEAN        NOT NULL,
    OPENED_ON DATE           NOT NULL
);

CREATE TABLE IF NOT EXISTS TRANSACTIONS (
    ID         INT PRIMARY KEY,
    ACCOUNT_ID INT            NOT NULL REFERENCES ACCOUNTS (ID),
    KIND       VARCHAR(20)    NOT NULL,
    AMOUNT     DECIMAL(15, 2) NOT NULL,
    CREATED_AT TIMESTAMP      NOT NULL
);

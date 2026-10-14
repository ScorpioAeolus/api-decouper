-- Dedicated SAMPLE TEST database only. The fixture owns its lifetime under a shared test-database lease.
DROP TABLE IF EXISTS game_credit_ledger;
DROP TABLE IF EXISTS game_recharge_order;
DROP TABLE IF EXISTS game_wallet;
CREATE TABLE game_wallet(player_id VARCHAR(80) COLLATE utf8mb4_bin PRIMARY KEY,coins BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE game_recharge_order(
 order_id VARCHAR(80) COLLATE utf8mb4_bin PRIMARY KEY,player_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 sku VARCHAR(80) NOT NULL,amount DECIMAL(18,2) NOT NULL,currency CHAR(3) NOT NULL,credit_units BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,channel_reference VARCHAR(128),KEY ix_player(player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE game_credit_ledger(order_id VARCHAR(80) COLLATE utf8mb4_bin PRIMARY KEY,player_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,credit_units BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

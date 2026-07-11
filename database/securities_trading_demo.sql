-- MyDataDev 本地证券交易演示库。
-- 注意：为保证可重复初始化，执行本脚本会删除并重建 securities_trading_demo。

DROP DATABASE IF EXISTS securities_trading_demo;
CREATE DATABASE securities_trading_demo
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE securities_trading_demo;

CREATE TABLE customers (
  customer_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_no VARCHAR(32) NOT NULL,
  full_name VARCHAR(80) NOT NULL,
  mobile VARCHAR(32) NOT NULL,
  email VARCHAR(120) NULL,
  status ENUM('ACTIVE', 'SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id),
  UNIQUE KEY uk_customers_customer_no (customer_no),
  UNIQUE KEY uk_customers_mobile (mobile)
) ENGINE=InnoDB;

CREATE TABLE trading_accounts (
  account_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_no VARCHAR(32) NOT NULL,
  customer_id BIGINT UNSIGNED NOT NULL,
  account_type ENUM('CASH', 'MARGIN') NOT NULL DEFAULT 'CASH',
  available_cash DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  frozen_cash DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  status ENUM('ACTIVE', 'FROZEN', 'CLOSED') NOT NULL DEFAULT 'ACTIVE',
  opened_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_trading_accounts_account_no (account_no),
  KEY idx_trading_accounts_customer_id (customer_id),
  CONSTRAINT fk_trading_accounts_customer
    FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
) ENGINE=InnoDB;

CREATE TABLE instruments (
  instrument_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(16) NOT NULL,
  exchange_code ENUM('SSE', 'SZSE') NOT NULL,
  instrument_name VARCHAR(80) NOT NULL,
  instrument_type ENUM('STOCK', 'ETF', 'BOND') NOT NULL DEFAULT 'STOCK',
  listing_date DATE NULL,
  status ENUM('ACTIVE', 'SUSPENDED', 'DELISTED') NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (instrument_id),
  UNIQUE KEY uk_instruments_exchange_symbol (exchange_code, symbol)
) ENGINE=InnoDB;

CREATE TABLE market_quotes (
  quote_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  instrument_id BIGINT UNSIGNED NOT NULL,
  trade_date DATE NOT NULL,
  last_price DECIMAL(12,2) NOT NULL,
  open_price DECIMAL(12,2) NOT NULL,
  high_price DECIMAL(12,2) NOT NULL,
  low_price DECIMAL(12,2) NOT NULL,
  previous_close DECIMAL(12,2) NOT NULL,
  volume BIGINT UNSIGNED NOT NULL,
  quote_time DATETIME NOT NULL,
  PRIMARY KEY (quote_id),
  UNIQUE KEY uk_market_quotes_instrument_date (instrument_id, trade_date),
  CONSTRAINT fk_market_quotes_instrument
    FOREIGN KEY (instrument_id) REFERENCES instruments (instrument_id)
) ENGINE=InnoDB;

CREATE TABLE positions (
  position_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_id BIGINT UNSIGNED NOT NULL,
  instrument_id BIGINT UNSIGNED NOT NULL,
  total_quantity BIGINT UNSIGNED NOT NULL DEFAULT 0,
  available_quantity BIGINT UNSIGNED NOT NULL DEFAULT 0,
  frozen_quantity BIGINT UNSIGNED NOT NULL DEFAULT 0,
  average_cost DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (position_id),
  UNIQUE KEY uk_positions_account_instrument (account_id, instrument_id),
  CONSTRAINT fk_positions_account
    FOREIGN KEY (account_id) REFERENCES trading_accounts (account_id),
  CONSTRAINT fk_positions_instrument
    FOREIGN KEY (instrument_id) REFERENCES instruments (instrument_id)
) ENGINE=InnoDB;

CREATE TABLE orders (
  order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(40) NOT NULL,
  account_id BIGINT UNSIGNED NOT NULL,
  instrument_id BIGINT UNSIGNED NOT NULL,
  side ENUM('BUY', 'SELL') NOT NULL,
  order_type ENUM('LIMIT', 'MARKET') NOT NULL DEFAULT 'LIMIT',
  limit_price DECIMAL(12,2) NULL,
  quantity BIGINT UNSIGNED NOT NULL,
  filled_quantity BIGINT UNSIGNED NOT NULL DEFAULT 0,
  status ENUM('NEW', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED') NOT NULL,
  submitted_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_orders_order_no (order_no),
  KEY idx_orders_account_status_time (account_id, status, submitted_at),
  KEY idx_orders_instrument_time (instrument_id, submitted_at),
  CONSTRAINT fk_orders_account
    FOREIGN KEY (account_id) REFERENCES trading_accounts (account_id),
  CONSTRAINT fk_orders_instrument
    FOREIGN KEY (instrument_id) REFERENCES instruments (instrument_id),
  CONSTRAINT chk_orders_filled_quantity CHECK (filled_quantity <= quantity)
) ENGINE=InnoDB;

CREATE TABLE trades (
  trade_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trade_no VARCHAR(40) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  execution_price DECIMAL(12,2) NOT NULL,
  quantity BIGINT UNSIGNED NOT NULL,
  commission DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  executed_at DATETIME NOT NULL,
  PRIMARY KEY (trade_id),
  UNIQUE KEY uk_trades_trade_no (trade_no),
  KEY idx_trades_order_id (order_id),
  CONSTRAINT fk_trades_order
    FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB;

CREATE TABLE cash_ledger (
  ledger_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_id BIGINT UNSIGNED NOT NULL,
  entry_type ENUM('DEPOSIT', 'WITHDRAWAL', 'TRADE_BUY', 'TRADE_SELL', 'COMMISSION', 'ORDER_FREEZE', 'ORDER_RELEASE') NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  balance_after DECIMAL(18,2) NOT NULL,
  reference_no VARCHAR(40) NULL,
  occurred_at DATETIME NOT NULL,
  remark VARCHAR(255) NULL,
  PRIMARY KEY (ledger_id),
  KEY idx_cash_ledger_account_time (account_id, occurred_at),
  CONSTRAINT fk_cash_ledger_account
    FOREIGN KEY (account_id) REFERENCES trading_accounts (account_id)
) ENGINE=InnoDB;

INSERT INTO customers (customer_no, full_name, mobile, email) VALUES
  ('C20260001', '林晓雨', '13800001001', 'xiaoyu.lin@example.test'),
  ('C20260002', '周明远', '13800001002', 'mingyuan.zhou@example.test'),
  ('C20260003', '陈思涵', '13800001003', 'sihan.chen@example.test');

INSERT INTO trading_accounts (account_no, customer_id, account_type, available_cash, frozen_cash, status, opened_at) VALUES
  ('A20260001', 1, 'CASH',   42184.01, 151820.00, 'ACTIVE', '2026-06-02 09:00:00'),
  ('A20260002', 2, 'CASH',   93037.26,  34200.00, 'ACTIVE', '2026-06-08 09:00:00'),
  ('A20260003', 3, 'MARGIN', 102637.36,     0.00, 'ACTIVE', '2026-06-15 09:00:00');

INSERT INTO instruments (symbol, exchange_code, instrument_name, instrument_type, listing_date) VALUES
  ('600519', 'SSE',  '贵州茅台', 'STOCK', '2001-08-27'),
  ('000001', 'SZSE', '平安银行', 'STOCK', '1991-04-03'),
  ('300750', 'SZSE', '宁德时代', 'STOCK', '2018-06-11'),
  ('601318', 'SSE',  '中国平安', 'STOCK', '2007-03-01'),
  ('688981', 'SSE',  '中芯国际', 'STOCK', '2020-07-16');

INSERT INTO market_quotes (instrument_id, trade_date, last_price, open_price, high_price, low_price, previous_close, volume, quote_time) VALUES
  (1, '2026-07-10', 1518.20, 1501.00, 1524.80, 1498.60, 1503.40,  6823412, '2026-07-10 15:00:00'),
  (2, '2026-07-10',   12.18,   12.04,   12.25,   11.98,   12.02, 87342109, '2026-07-10 15:00:00'),
  (3, '2026-07-10',  191.00,  186.20,  193.50,  185.80,  186.60, 22195684, '2026-07-10 15:00:00'),
  (4, '2026-07-10',    5.28,    5.22,    5.31,    5.19,    5.21, 65310781, '2026-07-10 15:00:00'),
  (5, '2026-07-10',   94.60,   92.80,   95.40,   92.30,   92.70, 18442603, '2026-07-10 15:00:00');

INSERT INTO positions (account_id, instrument_id, total_quantity, available_quantity, frozen_quantity, average_cost) VALUES
  (1, 1,  100,  100, 0, 1480.0000),
  (1, 2, 1000, 1000, 0,   11.8500),
  (2, 3,  120,  120, 0,  189.5000),
  (3, 4, 2000, 2000, 0,    5.0600),
  (3, 5,  300,  300, 0,   88.2000);

INSERT INTO orders (order_no, account_id, instrument_id, side, order_type, limit_price, quantity, filled_quantity, status, submitted_at) VALUES
  ('O202607100001', 1, 2, 'BUY',  'LIMIT',   12.10, 500, 500, 'FILLED',           '2026-07-10 09:35:12'),
  ('O202607100002', 1, 2, 'SELL', 'LIMIT',   12.30, 300,   0, 'CANCELED',         '2026-07-10 10:08:30'),
  ('O202607100003', 2, 3, 'BUY',  'LIMIT',  190.00, 300, 120, 'PARTIALLY_FILLED', '2026-07-10 10:16:45'),
  ('O202607100004', 3, 4, 'SELL', 'LIMIT',    5.25, 500, 500, 'FILLED',           '2026-07-10 11:02:17'),
  ('O202607100005', 1, 1, 'BUY',  'LIMIT', 1518.20, 100,   0, 'NEW',              '2026-07-10 14:53:08');

INSERT INTO trades (trade_no, order_id, execution_price, quantity, commission, executed_at) VALUES
  ('T202607100001', 1,  11.98, 500,  5.99, '2026-07-10 09:35:13'),
  ('T202607100002', 3, 189.50, 120, 22.74, '2026-07-10 10:17:02'),
  ('T202607100003', 4,   5.28, 500,  2.64, '2026-07-10 11:02:20');

INSERT INTO cash_ledger (account_id, entry_type, amount, balance_after, reference_no, occurred_at, remark) VALUES
  (1, 'DEPOSIT',      200000.00, 200000.00, 'D202607020001', '2026-07-02 09:00:00', '银证转入'),
  (1, 'TRADE_BUY',     -5990.00, 194010.00, 'T202607100001', '2026-07-10 09:35:13', '买入平安银行 500 股'),
  (1, 'COMMISSION',       -5.99, 194004.01, 'T202607100001', '2026-07-10 09:35:13', '买入手续费'),
  (1, 'ORDER_FREEZE', -151820.00,  42184.01, 'O202607100005', '2026-07-10 14:53:08', '买入贵州茅台委托冻结'),
  (2, 'DEPOSIT',      150000.00, 150000.00, 'D202607080001', '2026-07-08 09:00:00', '银证转入'),
  (2, 'TRADE_BUY',    -22740.00, 127260.00, 'T202607100002', '2026-07-10 10:17:02', '买入宁德时代 120 股'),
  (2, 'COMMISSION',      -22.74, 127237.26, 'T202607100002', '2026-07-10 10:17:02', '买入手续费'),
  (2, 'ORDER_FREEZE', -34200.00,  93037.26, 'O202607100003', '2026-07-10 10:16:45', '未成交买入委托冻结'),
  (3, 'DEPOSIT',      100000.00, 100000.00, 'D202606150001', '2026-06-15 09:00:00', '银证转入'),
  (3, 'TRADE_SELL',     2640.00, 102640.00, 'T202607100003', '2026-07-10 11:02:20', '卖出中国平安 500 股'),
  (3, 'COMMISSION',       -2.64, 102637.36, 'T202607100003', '2026-07-10 11:02:20', '卖出手续费');

-- 常用浏览视图：账户当前持仓市值和浮动盈亏。
CREATE VIEW account_position_summary AS
SELECT
  a.account_no,
  c.full_name AS customer_name,
  i.exchange_code,
  i.symbol,
  i.instrument_name,
  p.total_quantity,
  p.available_quantity,
  p.frozen_quantity,
  p.average_cost,
  q.last_price,
  ROUND(p.total_quantity * q.last_price, 2) AS market_value,
  ROUND(p.total_quantity * (q.last_price - p.average_cost), 2) AS unrealized_pnl
FROM positions p
JOIN trading_accounts a ON a.account_id = p.account_id
JOIN customers c ON c.customer_id = a.customer_id
JOIN instruments i ON i.instrument_id = p.instrument_id
JOIN market_quotes q ON q.instrument_id = i.instrument_id
  AND q.trade_date = '2026-07-10';

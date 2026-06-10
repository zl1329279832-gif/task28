-- ============================================================
-- Membership Points & Benefits System - Database Schema
-- Engine: InnoDB | Charset: utf8mb4
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------
-- 1. member_level - membership tier definitions
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `member_level`;
CREATE TABLE `member_level` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT                        COMMENT 'primary key',
    `level_code`       VARCHAR(20)  NOT NULL                                       COMMENT 'level code: BRONZE/SILVER/GOLD/PLATINUM',
    `level_name`       VARCHAR(50)  NOT NULL                                       COMMENT 'display name of the level',
    `min_points`       INT          NOT NULL DEFAULT 0                             COMMENT 'minimum accumulated points to reach this level',
    `max_points`       INT          NOT NULL DEFAULT 0                             COMMENT 'upper bound of points for this level, 0 means unlimited',
    `level_multiplier` DECIMAL(3,2) NOT NULL DEFAULT 1.00                          COMMENT 'points earning multiplier for this level',
    `sort_order`       INT          NOT NULL DEFAULT 0                             COMMENT 'display sort order',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP             COMMENT 'record creation time',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_level_code` (`level_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='membership level definition table';

-- -----------------------------------------------------------
-- 2. member - member master data
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `member`;
CREATE TABLE `member` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT                      COMMENT 'primary key',
    `member_no`          VARCHAR(32)  NOT NULL                                     COMMENT 'unique member number',
    `name`               VARCHAR(100) NOT NULL                                     COMMENT 'member name',
    `phone`              VARCHAR(20)           DEFAULT NULL                        COMMENT 'phone number',
    `email`              VARCHAR(100)          DEFAULT NULL                        COMMENT 'email address',
    `level_id`           BIGINT       NOT NULL                                     COMMENT 'current level id, FK to member_level',
    `accumulated_points` BIGINT       NOT NULL DEFAULT 0                           COMMENT 'lifetime total earned points, used for level calculation',
    `register_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP           COMMENT 'registration time',
    `status`             TINYINT      NOT NULL DEFAULT 1                           COMMENT 'account status: 1=active, 0=inactive',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP           COMMENT 'record creation time',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_no` (`member_no`),
    INDEX `idx_phone` (`phone`),
    INDEX `idx_level_id` (`level_id`),
    CONSTRAINT `fk_member_level` FOREIGN KEY (`level_id`) REFERENCES `member_level` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='member master table';

-- -----------------------------------------------------------
-- 3. points_account - member points wallet
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_account`;
CREATE TABLE `points_account` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT                             COMMENT 'primary key',
    `member_id`       BIGINT   NOT NULL                                            COMMENT 'member id, FK to member',
    `available_points` BIGINT  NOT NULL DEFAULT 0                                  COMMENT 'currently available points',
    `frozen_points`   BIGINT   NOT NULL DEFAULT 0                                  COMMENT 'currently frozen (reserved) points',
    `total_earned`    BIGINT   NOT NULL DEFAULT 0                                  COMMENT 'lifetime total earned points',
    `total_spent`     BIGINT   NOT NULL DEFAULT 0                                  COMMENT 'lifetime total spent points',
    `total_expired`   BIGINT   NOT NULL DEFAULT 0                                  COMMENT 'lifetime total expired points',
    `version`         INT      NOT NULL DEFAULT 0                                  COMMENT 'optimistic lock version',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP                  COMMENT 'record creation time',
    `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_id` (`member_id`),
    CONSTRAINT `fk_points_account_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='member points account (wallet) table';

-- -----------------------------------------------------------
-- 4. points_transaction - points transaction journal
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_transaction`;
CREATE TABLE `points_transaction` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT                         COMMENT 'primary key',
    `transaction_no`   VARCHAR(64) NOT NULL                                        COMMENT 'unique transaction number',
    `member_id`        BIGINT      NOT NULL                                        COMMENT 'member id',
    `transaction_type` VARCHAR(20) NOT NULL                                        COMMENT 'type: EARN/SPEND/EXPIRE/ADJUST_ADD/ADJUST_DEDUCT/FREEZE/UNFREEZE',
    `source`           VARCHAR(30) NOT NULL                                        COMMENT 'source: REGISTER/CHECKIN/PURCHASE/ACTIVITY/ADMIN/REDEMPTION/EXPIRATION/REFUND',
    `points_amount`    BIGINT      NOT NULL                                        COMMENT 'points amount, positive=credit, negative=debit',
    `balance_before`   BIGINT      NOT NULL                                        COMMENT 'available balance before this transaction',
    `balance_after`    BIGINT      NOT NULL                                        COMMENT 'available balance after this transaction',
    `frozen_before`    BIGINT               DEFAULT NULL                           COMMENT 'frozen balance before this transaction',
    `frozen_after`     BIGINT               DEFAULT NULL                           COMMENT 'frozen balance after this transaction',
    `rule_version_id`  BIGINT               DEFAULT NULL                           COMMENT 'applied rule version id',
    `batch_id`         BIGINT               DEFAULT NULL                           COMMENT 'related points batch id',
    `reference_id`     VARCHAR(64)          DEFAULT NULL                           COMMENT 'external reference id (order no, activity id, etc.)',
    `remark`           VARCHAR(500)         DEFAULT NULL                           COMMENT 'transaction remark',
    `operator`         VARCHAR(50)          DEFAULT NULL                           COMMENT 'operator who triggered this transaction',
    `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP              COMMENT 'transaction time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transaction_no` (`transaction_no`),
    INDEX `idx_member_id` (`member_id`),
    INDEX `idx_transaction_type` (`transaction_type`),
    INDEX `idx_reference_id` (`reference_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_batch_id` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='points transaction journal table';

-- -----------------------------------------------------------
-- 5. points_batch - points batch with expiry tracking
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_batch`;
CREATE TABLE `points_batch` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT                      COMMENT 'primary key',
    `member_id`           BIGINT      NOT NULL                                     COMMENT 'member id',
    `source`              VARCHAR(30) NOT NULL                                     COMMENT 'earning source',
    `original_points`     BIGINT      NOT NULL                                     COMMENT 'original earned points in this batch',
    `remaining_points`    BIGINT      NOT NULL                                     COMMENT 'remaining available points in this batch',
    `frozen_points`       BIGINT      NOT NULL DEFAULT 0                           COMMENT 'frozen points in this batch',
    `rule_version_id`     BIGINT               DEFAULT NULL                        COMMENT 'rule version applied when earned',
    `earn_transaction_id` BIGINT      NOT NULL                                     COMMENT 'the earning transaction id',
    `earned_at`           DATETIME    NOT NULL                                     COMMENT 'time when points were earned',
    `expire_at`           DATETIME    NOT NULL                                     COMMENT 'time when this batch expires',
    `expired`             TINYINT     NOT NULL DEFAULT 0                           COMMENT 'expired flag: 0=active, 1=expired',
    `created_at`          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP           COMMENT 'record creation time',
    `updated_at`          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    INDEX `idx_member_id` (`member_id`),
    INDEX `idx_expire_at_expired` (`expire_at`, `expired`),
    INDEX `idx_member_expired_expire` (`member_id`, `expired`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='points batch table, tracks per-batch expiry';

-- -----------------------------------------------------------
-- 6. points_freeze - freeze/unfreeze records
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_freeze`;
CREATE TABLE `points_freeze` (
    `id`                    BIGINT      NOT NULL AUTO_INCREMENT                    COMMENT 'primary key',
    `freeze_no`             VARCHAR(64) NOT NULL                                   COMMENT 'unique freeze record number',
    `member_id`             BIGINT      NOT NULL                                   COMMENT 'member id',
    `frozen_points`         BIGINT      NOT NULL                                   COMMENT 'total frozen points in this record',
    `status`                VARCHAR(20) NOT NULL                                   COMMENT 'status: FROZEN/UNFROZEN_SUCCESS/UNFROZEN_FAIL',
    `redemption_order_id`   BIGINT               DEFAULT NULL                     COMMENT 'related redemption order id',
    `freeze_detail`         TEXT                  DEFAULT NULL                     COMMENT 'JSON detail: [{batchId, frozenAmount}]',
    `frozen_at`             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP         COMMENT 'freeze time',
    `unfrozen_at`           DATETIME             DEFAULT NULL                      COMMENT 'unfreeze time',
    `created_at`            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP         COMMENT 'record creation time',
    `updated_at`            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_freeze_no` (`freeze_no`),
    INDEX `idx_member_id` (`member_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_redemption_order_id` (`redemption_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='points freeze/unfreeze record table';

-- -----------------------------------------------------------
-- 7. points_rule - points earning rule configuration
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_rule`;
CREATE TABLE `points_rule` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT                         COMMENT 'primary key',
    `rule_code`       VARCHAR(50)  NOT NULL                                        COMMENT 'unique rule code, e.g. REGISTER_BONUS',
    `rule_name`       VARCHAR(100) NOT NULL                                        COMMENT 'rule display name',
    `source`          VARCHAR(30)  NOT NULL                                        COMMENT 'earning source: REGISTER/CHECKIN/PURCHASE/ACTIVITY',
    `base_points`     INT          NOT NULL DEFAULT 0                              COMMENT 'base points awarded',
    `multiplier`      DECIMAL(5,2) NOT NULL DEFAULT 1.00                           COMMENT 'points multiplier',
    `points_per_yuan` INT                   DEFAULT NULL                           COMMENT 'points per yuan spent (for purchase rules)',
    `monthly_cap`     INT                   DEFAULT NULL                           COMMENT 'monthly earning cap, NULL means unlimited',
    `expire_months`   INT          NOT NULL DEFAULT 12                             COMMENT 'number of months until points expire',
    `enabled`         TINYINT      NOT NULL DEFAULT 1                              COMMENT 'rule enabled: 1=yes, 0=no',
    `current_version` INT          NOT NULL DEFAULT 1                              COMMENT 'current active version number',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP              COMMENT 'record creation time',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_code` (`rule_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='points earning rule configuration table';

-- -----------------------------------------------------------
-- 8. points_rule_version - versioned snapshots of rules
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `points_rule_version`;
CREATE TABLE `points_rule_version` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT                         COMMENT 'primary key',
    `rule_id`         BIGINT       NOT NULL                                        COMMENT 'parent rule id, FK to points_rule',
    `version_number`  INT          NOT NULL                                        COMMENT 'version number within the rule',
    `base_points`     INT          NOT NULL DEFAULT 0                              COMMENT 'base points in this version',
    `multiplier`      DECIMAL(5,2) NOT NULL DEFAULT 1.00                           COMMENT 'multiplier in this version',
    `points_per_yuan` INT                   DEFAULT NULL                           COMMENT 'points per yuan in this version',
    `monthly_cap`     INT                   DEFAULT NULL                           COMMENT 'monthly cap in this version',
    `expire_months`   INT          NOT NULL DEFAULT 12                             COMMENT 'expire months in this version',
    `snapshot_json`   TEXT         NOT NULL                                        COMMENT 'full JSON snapshot of the rule at this version',
    `effective_from`  DATETIME     NOT NULL                                        COMMENT 'version effective start time',
    `effective_to`    DATETIME              DEFAULT NULL                           COMMENT 'version effective end time, NULL means current',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP              COMMENT 'record creation time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_version` (`rule_id`, `version_number`),
    INDEX `idx_rule_effective` (`rule_id`, `effective_from`),
    CONSTRAINT `fk_rule_version_rule` FOREIGN KEY (`rule_id`) REFERENCES `points_rule` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='points rule version snapshot table';

-- -----------------------------------------------------------
-- 9. benefit - redeemable benefit catalog
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `benefit`;
CREATE TABLE `benefit` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT                           COMMENT 'primary key',
    `benefit_code`  VARCHAR(50)  NOT NULL                                          COMMENT 'unique benefit code',
    `benefit_name`  VARCHAR(200) NOT NULL                                          COMMENT 'benefit display name',
    `description`   TEXT                  DEFAULT NULL                             COMMENT 'benefit description',
    `category`      VARCHAR(50)           DEFAULT NULL                             COMMENT 'benefit category',
    `points_cost`   INT          NOT NULL                                          COMMENT 'points required to redeem',
    `min_level`     VARCHAR(20)           DEFAULT NULL                             COMMENT 'minimum member level required, NULL means any level',
    `image_url`     VARCHAR(500)          DEFAULT NULL                             COMMENT 'benefit image URL',
    `enabled`       TINYINT      NOT NULL DEFAULT 1                                COMMENT 'enabled: 1=yes, 0=no',
    `start_time`    DATETIME              DEFAULT NULL                             COMMENT 'benefit availability start time',
    `end_time`      DATETIME              DEFAULT NULL                             COMMENT 'benefit availability end time',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                COMMENT 'record creation time',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_benefit_code` (`benefit_code`),
    INDEX `idx_category` (`category`),
    INDEX `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='redeemable benefit catalog table';

-- -----------------------------------------------------------
-- 10. benefit_inventory - benefit stock management
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `benefit_inventory`;
CREATE TABLE `benefit_inventory` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT                          COMMENT 'primary key',
    `benefit_id`      BIGINT      NOT NULL                                         COMMENT 'benefit id, FK to benefit',
    `sku_code`        VARCHAR(50) NOT NULL                                         COMMENT 'unique SKU code',
    `sku_name`        VARCHAR(200)         DEFAULT NULL                            COMMENT 'SKU display name',
    `total_stock`     INT         NOT NULL DEFAULT 0                               COMMENT 'total stock quantity',
    `available_stock`  INT        NOT NULL DEFAULT 0                               COMMENT 'currently available stock',
    `frozen_stock`    INT         NOT NULL DEFAULT 0                               COMMENT 'frozen (reserved) stock',
    `version`         INT         NOT NULL DEFAULT 0                               COMMENT 'optimistic lock version',
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT 'record creation time',
    `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_code` (`sku_code`),
    INDEX `idx_benefit_id` (`benefit_id`),
    CONSTRAINT `fk_inventory_benefit` FOREIGN KEY (`benefit_id`) REFERENCES `benefit` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='benefit inventory / stock management table';

-- -----------------------------------------------------------
-- 11. redemption_order - benefit redemption orders
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `redemption_order`;
CREATE TABLE `redemption_order` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT                            COMMENT 'primary key',
    `order_no`      VARCHAR(64) NOT NULL                                           COMMENT 'unique order number',
    `member_id`     BIGINT      NOT NULL                                           COMMENT 'member id',
    `benefit_id`    BIGINT      NOT NULL                                           COMMENT 'benefit id',
    `inventory_id`  BIGINT      NOT NULL                                           COMMENT 'inventory (SKU) id',
    `quantity`      INT         NOT NULL DEFAULT 1                                 COMMENT 'redemption quantity',
    `points_cost`   INT         NOT NULL                                           COMMENT 'total points cost for this order',
    `status`        VARCHAR(20) NOT NULL                                           COMMENT 'order status: PENDING/FROZEN/COMPLETED/FAILED/REFUNDED',
    `freeze_id`     BIGINT               DEFAULT NULL                             COMMENT 'related points freeze record id',
    `refund_reason` VARCHAR(500)         DEFAULT NULL                              COMMENT 'refund reason if refunded',
    `refunded_at`   DATETIME             DEFAULT NULL                              COMMENT 'refund time',
    `completed_at`  DATETIME             DEFAULT NULL                              COMMENT 'completion time',
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP                 COMMENT 'record creation time',
    `updated_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    INDEX `idx_member_id` (`member_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_benefit_id` (`benefit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='benefit redemption order table';

-- -----------------------------------------------------------
-- 12. blacklist - member blacklist / block rules
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `blacklist`;
CREATE TABLE `blacklist` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT                             COMMENT 'primary key',
    `member_id`    BIGINT      NOT NULL                                            COMMENT 'member id',
    `block_type`   VARCHAR(20) NOT NULL                                            COMMENT 'block type: EARN_BLOCK/REDEEM_BLOCK/FULL_BLOCK',
    `reason`       VARCHAR(500) NOT NULL                                           COMMENT 'reason for blocking',
    `operator`     VARCHAR(50) NOT NULL                                            COMMENT 'operator who applied the block',
    `blocked_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP                  COMMENT 'block time',
    `unblocked_at` DATETIME             DEFAULT NULL                               COMMENT 'unblock time',
    `active`       TINYINT     NOT NULL DEFAULT 1                                  COMMENT 'active flag: 1=active block, 0=removed',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP                  COMMENT 'record creation time',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'record last update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_block_active` (`member_id`, `block_type`, `active`),
    INDEX `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='member blacklist / block rule table';

-- -----------------------------------------------------------
-- 13. audit_log - system audit trail
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT                              COMMENT 'primary key',
    `operation`   VARCHAR(50) NOT NULL                                             COMMENT 'operation type performed',
    `target_type` VARCHAR(30) NOT NULL                                             COMMENT 'target entity type',
    `target_id`   BIGINT      NOT NULL                                             COMMENT 'target entity id',
    `member_id`   BIGINT               DEFAULT NULL                                COMMENT 'related member id if applicable',
    `operator`    VARCHAR(50)          DEFAULT NULL                                COMMENT 'operator who performed the action',
    `detail`      TEXT                 DEFAULT NULL                                COMMENT 'JSON detail of the operation',
    `ip_address`  VARCHAR(45)          DEFAULT NULL                                COMMENT 'client IP address (supports IPv6)',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP                   COMMENT 'operation time',
    PRIMARY KEY (`id`),
    INDEX `idx_member_id` (`member_id`),
    INDEX `idx_operation` (`operation`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='system audit log table';

-- -----------------------------------------------------------
-- 14. idempotent_record - idempotency control
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `idempotent_record`;
CREATE TABLE `idempotent_record` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT                         COMMENT 'primary key',
    `idempotent_key`  VARCHAR(128) NOT NULL                                        COMMENT 'unique idempotency key',
    `business_type`   VARCHAR(30)  NOT NULL                                        COMMENT 'business type of the operation',
    `result_status`   VARCHAR(20)  NOT NULL                                        COMMENT 'result: SUCCESS/FAIL',
    `result_data`     TEXT                  DEFAULT NULL                           COMMENT 'JSON result data',
    `expire_at`       DATETIME     NOT NULL                                        COMMENT 'record expiration time for cleanup',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP              COMMENT 'record creation time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotent_key` (`idempotent_key`),
    INDEX `idx_expire_at` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='idempotent request deduplication table';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- Seed Data
-- ============================================================

-- -----------------------------------------------------------
-- member_level seed data
-- -----------------------------------------------------------
INSERT INTO `member_level` (`level_code`, `level_name`, `min_points`, `max_points`, `level_multiplier`, `sort_order`)
VALUES
    ('BRONZE',   '青铜', 0,     999,   1.00, 1),
    ('SILVER',   '白银', 1000,  4999,  1.20, 2),
    ('GOLD',     '黄金', 5000,  19999, 1.50, 3),
    ('PLATINUM', '铂金', 20000, 0,     2.00, 4);

-- -----------------------------------------------------------
-- points_rule seed data
-- -----------------------------------------------------------
INSERT INTO `points_rule` (`id`, `rule_code`, `rule_name`, `source`, `base_points`, `multiplier`, `points_per_yuan`, `monthly_cap`, `expire_months`, `enabled`, `current_version`)
VALUES
    (1, 'REGISTER_BONUS',   '注册赠送',   'REGISTER', 100, 1.00, NULL, NULL, 12, 1, 1),
    (2, 'DAILY_CHECKIN',    '每日签到',   'CHECKIN',  10,  1.00, NULL, 300,  12, 1, 1),
    (3, 'PURCHASE_CASHBACK','消费返积分', 'PURCHASE', 0,   1.00, 1,    5000, 12, 1, 1),
    (4, 'ACTIVITY_BONUS',   '活动奖励',   'ACTIVITY', 0,   2.00, NULL, NULL, 6,  1, 1);

-- -----------------------------------------------------------
-- points_rule_version seed data (initial v1 for each rule)
-- -----------------------------------------------------------
INSERT INTO `points_rule_version` (`rule_id`, `version_number`, `base_points`, `multiplier`, `points_per_yuan`, `monthly_cap`, `expire_months`, `snapshot_json`, `effective_from`, `effective_to`)
VALUES
    (1, 1, 100, 1.00, NULL, NULL, 12,
     '{"rule_code":"REGISTER_BONUS","rule_name":"注册赠送","source":"REGISTER","base_points":100,"multiplier":1.00,"points_per_yuan":null,"monthly_cap":null,"expire_months":12}',
     NOW(), NULL),
    (2, 1, 10, 1.00, NULL, 300, 12,
     '{"rule_code":"DAILY_CHECKIN","rule_name":"每日签到","source":"CHECKIN","base_points":10,"multiplier":1.00,"points_per_yuan":null,"monthly_cap":300,"expire_months":12}',
     NOW(), NULL),
    (3, 1, 0, 1.00, 1, 5000, 12,
     '{"rule_code":"PURCHASE_CASHBACK","rule_name":"消费返积分","source":"PURCHASE","base_points":0,"multiplier":1.00,"points_per_yuan":1,"monthly_cap":5000,"expire_months":12}',
     NOW(), NULL),
    (4, 1, 0, 2.00, NULL, NULL, 6,
     '{"rule_code":"ACTIVITY_BONUS","rule_name":"活动奖励","source":"ACTIVITY","base_points":0,"multiplier":2.00,"points_per_yuan":null,"monthly_cap":null,"expire_months":6}',
     NOW(), NULL);

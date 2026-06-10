-- ============================================
-- 会员积分与权益系统 - 数据库建表脚本
-- ============================================

CREATE DATABASE IF NOT EXISTS points_system DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE points_system;

-- 1. 会员等级表
CREATE TABLE member_level (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    level_code    INT          NOT NULL COMMENT '等级编码 1-5',
    level_name    VARCHAR(50)  NOT NULL COMMENT '等级名称',
    min_exp       BIGINT       NOT NULL DEFAULT 0 COMMENT '升级所需最低经验值',
    earn_rate     DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '积分获取倍率',
    redeem_rate   DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '积分兑换折扣',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_level_code (level_code)
) COMMENT='会员等级配置';

-- 2. 积分账户表
CREATE TABLE points_account (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    member_id          BIGINT   NOT NULL COMMENT '会员ID',
    available_points    BIGINT   NOT NULL DEFAULT 0 COMMENT '可用积分',
    frozen_points      BIGINT   NOT NULL DEFAULT 0 COMMENT '冻结积分',
    total_earned       BIGINT   NOT NULL DEFAULT 0 COMMENT '累计获得',
    total_consumed     BIGINT   NOT NULL DEFAULT 0 COMMENT '累计消费',
    total_expired      BIGINT   NOT NULL DEFAULT 0 COMMENT '累计过期',
    level_id           BIGINT   DEFAULT NULL COMMENT '当前等级ID',
    monthly_earned     BIGINT   NOT NULL DEFAULT 0 COMMENT '本月已获取(上限控制)',
    monthly_reset_date DATE     DEFAULT NULL COMMENT '月度重置日',
    status             TINYINT  NOT NULL DEFAULT 1 COMMENT '1正常 0冻结',
    create_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_id (member_id)
) COMMENT='积分账户';

-- 3. 积分流水表
CREATE TABLE points_flow (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    member_id      BIGINT       NOT NULL COMMENT '会员ID',
    event_id       VARCHAR(64)  NOT NULL COMMENT '幂等事件ID',
    event_type     VARCHAR(30)  NOT NULL COMMENT 'REGISTER/PURCHASE/CHECKIN/ACTIVITY/ADJUST/FREEZE/UNFREEZE/REDEEM/REFUND/EXPIRE',
    points_change  BIGINT       NOT NULL COMMENT '积分变动(+增 -减)',
    before_points  BIGINT       NOT NULL COMMENT '变动前积分',
    after_points   BIGINT       NOT NULL COMMENT '变动后积分',
    rule_id        BIGINT       DEFAULT NULL COMMENT '关联规则ID',
    rule_version   INT          DEFAULT NULL COMMENT '规则版本号',
    biz_order_no   VARCHAR(64)  DEFAULT NULL COMMENT '业务订单号',
    expire_time    DATETIME     DEFAULT NULL COMMENT '本批次过期时间',
    remark         VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_member_id (member_id),
    INDEX idx_biz_order_no (biz_order_no),
    INDEX idx_expire_time (expire_time)
) COMMENT='积分流水';

-- 4. 积分冻结表
CREATE TABLE points_freeze (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL COMMENT '会员ID',
    freeze_no    VARCHAR(64)  NOT NULL COMMENT '冻结单号',
    points       BIGINT       NOT NULL COMMENT '冻结积分',
    biz_order_no VARCHAR(64)  DEFAULT NULL COMMENT '业务订单号',
    reason       VARCHAR(255) DEFAULT NULL COMMENT '冻结原因',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0冻结中 1已解冻 2已扣减 3已过期',
    expire_time  DATETIME     DEFAULT NULL COMMENT '自动解冻时间',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_freeze_no (freeze_no),
    INDEX idx_member_id (member_id),
    INDEX idx_status_expire (status, expire_time)
) COMMENT='积分冻结记录';

-- 5. 积分规则表(版本管理)
CREATE TABLE points_rule (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    rule_code       VARCHAR(50)  NOT NULL COMMENT '规则编码',
    rule_name       VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type       VARCHAR(30)  NOT NULL COMMENT '规则类型',
    rule_value      VARCHAR(500) NOT NULL COMMENT '规则值(JSON)',
    priority        INT          NOT NULL DEFAULT 0 COMMENT '优先级',
    version         INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    effective_start DATETIME     DEFAULT NULL COMMENT '生效开始',
    effective_end   DATETIME     DEFAULT NULL COMMENT '生效结束',
    description     VARCHAR(500) DEFAULT NULL COMMENT '规则说明',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rule_code_version (rule_code, version),
    INDEX idx_status (status)
) COMMENT='积分规则配置';

-- 6. 权益表
CREATE TABLE benefit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    benefit_name    VARCHAR(100) NOT NULL COMMENT '权益名称',
    benefit_type    VARCHAR(30)  NOT NULL COMMENT 'COUPON/GIFT/VIP_SERVICE/PHYSICAL',
    points_cost     BIGINT       NOT NULL COMMENT '兑换所需积分',
    total_stock     INT          NOT NULL COMMENT '总库存',
    available_stock INT          NOT NULL COMMENT '可用库存',
    min_level_id    BIGINT       DEFAULT NULL COMMENT '最低等级要求',
    daily_limit     INT          DEFAULT 0 COMMENT '每人每日兑换上限(0不限)',
    total_limit     INT          DEFAULT 0 COMMENT '每人总兑换上限(0不限)',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    start_time      DATETIME     DEFAULT NULL COMMENT '上架时间',
    end_time        DATETIME     DEFAULT NULL COMMENT '下架时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) COMMENT='权益商品';

-- 7. 兑换记录表
CREATE TABLE exchange_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL COMMENT '会员ID',
    benefit_id   BIGINT       NOT NULL COMMENT '权益ID',
    exchange_no  VARCHAR(64)  NOT NULL COMMENT '兑换单号',
    points_cost  BIGINT       NOT NULL COMMENT '消耗积分',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0兑换中 1已完成 2已取消 3已退款',
    biz_order_no VARCHAR(64)  DEFAULT NULL COMMENT '业务订单号',
    refund_time  DATETIME     DEFAULT NULL COMMENT '退款时间',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exchange_no (exchange_no),
    INDEX idx_member_benefit (member_id, benefit_id),
    INDEX idx_biz_order_no (biz_order_no)
) COMMENT='兑换记录';

-- 8. 黑名单表
CREATE TABLE blacklist (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    member_id   BIGINT       NOT NULL COMMENT '会员ID',
    reason      VARCHAR(255) DEFAULT NULL COMMENT '拉黑原因',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1生效 0解除',
    operator    VARCHAR(50)  DEFAULT NULL COMMENT '操作人',
    expire_time DATETIME     DEFAULT NULL COMMENT '自动解除时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_member_status (member_id, status)
) COMMENT='黑名单';

-- 9. 审计日志表
CREATE TABLE audit_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    module       VARCHAR(50)  NOT NULL COMMENT '模块',
    action       VARCHAR(50)  NOT NULL COMMENT '操作',
    target_id    VARCHAR(64)  DEFAULT NULL COMMENT '对象ID',
    target_type  VARCHAR(30)  DEFAULT NULL COMMENT '对象类型',
    before_value TEXT         DEFAULT NULL COMMENT '操作前(JSON)',
    after_value  TEXT         DEFAULT NULL COMMENT '操作后(JSON)',
    operator     VARCHAR(50)  DEFAULT NULL COMMENT '操作人',
    ip           VARCHAR(50)  DEFAULT NULL COMMENT 'IP',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_module_target (module, target_id),
    INDEX idx_operator (operator),
    INDEX idx_create_time (create_time)
) COMMENT='审计日志';

-- ============================================
-- 初始化数据
-- ============================================

INSERT INTO member_level (level_code, level_name, min_exp, earn_rate, redeem_rate) VALUES
(1, '普通会员', 0,     1.00, 1.00),
(2, '银卡会员', 1000,  1.20, 0.95),
(3, '金卡会员', 5000,  1.50, 0.90),
(4, '铂金会员', 20000, 1.80, 0.85),
(5, '钻石会员', 50000, 2.00, 0.80);

INSERT INTO points_rule (rule_code, rule_name, rule_type, rule_value, priority, version, description) VALUES
('REGISTER',    '注册赠送',   'REGISTER',    '{"points": 100}',                                      10, 1, '新会员注册赠送100积分'),
('PURCHASE',    '消费返积分', 'PURCHASE',    '{"rate": 1, "unit": "YUAN", "minAmount": 10}',          20, 1, '每消费1元返1积分'),
('CHECKIN',     '每日签到',   'CHECKIN',     '{"points": 5, "continuousBonus": [0,0,5,5,10,10,20]}', 15, 1, '签到5积分+连续签到奖励'),
('ACTIVITY',    '活动加倍',   'ACTIVITY',    '{"multiplier": 2.0, "activityCode": "DOUBLE11"}',      30, 1, '活动期间积分翻倍'),
('LEVEL_BONUS', '等级倍率',   'LEVEL_BONUS', '{"useMemberLevel": true}',                              25, 1, '根据会员等级倍率'),
('MONTHLY_CAP', '每月上限',   'MONTHLY_CAP', '{"maxPoints": 10000}',                                  99, 1, '每月最多获取10000积分');

INSERT INTO benefit (benefit_name, benefit_type, points_cost, total_stock, available_stock, min_level_id, daily_limit, total_limit) VALUES
('10元优惠券',   'COUPON',      500,  10000, 10000, 1, 1, 5),
('50元优惠券',   'COUPON',      2000, 5000,  5000,  2, 1, 3),
('品牌定制礼品', 'PHYSICAL',    5000, 500,   500,   3, 0, 1),
('VIP月卡',      'VIP_SERVICE', 3000, 2000,  2000,  2, 1, 6);

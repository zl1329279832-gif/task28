# 会员积分与权益系统

统一的会员积分和权益管理后端系统，解决各业务系统积分规则不一致、重复发放、库存扣减失败等问题。

## 技术栈

- Java 11 + Spring Boot 2.7
- MyBatis Plus 3.5
- MySQL 8.0
- Redis 6+ (缓存 + 分布式锁)
- Redisson (分布式锁)

## 核心功能

- **会员等级**：青铜/白银/黄金/铂金，自动升降级
- **积分账户**：可用余额、冻结余额、乐观锁并发控制
- **积分发放**：注册赠送、每日签到、消费返积分、活动奖励，支持等级倍率和月度上限
- **积分兑换**：Redis分布式锁 + DB乐观锁双重防超兑，FIFO批次扣减
- **积分冻结/解冻**：支持两阶段兑换流程
- **积分过期**：定时任务批量处理，按批次FIFO过期
- **退款返还**：按规则判断积分是否退回
- **权益管理**：SKU级库存管理，乐观锁防超卖
- **黑名单**：支持限制发放/兑换/全部
- **幂等控制**：唯一键去重，防重复发放
- **规则版本管理**：规则变更保留历史版本
- **审计日志**：全操作审计追踪

## 本地运行

### 前置条件

1. JDK 11+
2. Maven 3.6+
3. MySQL 8.0
4. Redis 6+

### 步骤

1. 创建数据库并执行DDL：
   ```bash
   mysql -u root -p -e "CREATE DATABASE membership_points DEFAULT CHARACTER SET utf8mb4"
   mysql -u root -p membership_points < sql/schema.sql
   ```

2. 配置数据库连接（修改 application.yml 或使用环境变量）：
   ```bash
   export DB_PASSWORD=your_password
   export REDIS_HOST=localhost
   export REDIS_PORT=6379
   ```

3. 编译和运行：
   ```bash
   mvn clean compile
   mvn spring-boot:run
   ```

4. 访问API文档：http://localhost:8080/swagger-ui.html

### 运行测试

```bash
mvn test
```

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/points/earn | 积分发放 |
| POST | /api/points/adjust | 人工调整 |
| POST | /api/points/freeze | 积分冻结 |
| POST | /api/points/unfreeze | 积分解冻 |
| GET | /api/points/account/{memberId} | 查询积分账户 |
| GET | /api/points/transactions | 查询积分流水 |
| POST | /api/benefits | 创建权益 |
| GET | /api/benefits | 权益列表 |
| POST | /api/redemption/redeem | 权益兑换 |
| POST | /api/redemption/refund/{orderId} | 退款 |
| GET | /api/member/{memberId}/level | 查询会员等级 |
| POST | /api/blacklist | 添加黑名单 |
| DELETE | /api/blacklist/{memberId} | 移除黑名单 |

## 数据库表

| 表名 | 说明 |
|------|------|
| member_level | 等级定义 |
| member | 会员信息 |
| points_account | 积分账户 |
| points_transaction | 积分流水 |
| points_batch | 积分批次(FIFO过期) |
| points_freeze | 冻结记录 |
| points_rule | 积分规则 |
| points_rule_version | 规则版本快照 |
| benefit | 权益目录 |
| benefit_inventory | 权益库存 |
| redemption_order | 兑换订单 |
| blacklist | 黑名单 |
| audit_log | 审计日志 |
| idempotent_record | 幂等去重 |

## 并发安全设计

1. **Redis分布式锁**：同一会员兑换请求串行化
2. **DB乐观锁**：积分账户和权益库存使用version字段
3. **幂等去重**：数据库唯一键 + 业务层校验
4. **FIFO批次扣减**：积分按获取时间先进先出消费

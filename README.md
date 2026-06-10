# 会员积分与权益系统

## 技术栈

- **框架**: Spring Boot 2.7.18 + MyBatis Plus 3.5.5
- **数据库**: MySQL 8.0
- **缓存/分布式锁**: Redis + Redisson 3.25.2
- **构建**: Maven, Java 17

## 项目结构

```
src/main/java/com/example/points/
├── PointsApplication.java          # 启动类
├── common/                         # 通用组件 (ApiResponse, BusinessException, 全局异常处理)
├── config/                         # 配置 (MyBatis Plus 分页, Redis)
├── controller/                     # REST API 控制器
│   ├── AccountController.java      # 账户查询
│   ├── PointsController.java       # 积分发放/调整/退款/冻结
│   ├── BenefitController.java      # 权益列表/兑换/退款
│   ├── RuleController.java         # 规则管理
│   └── BlacklistController.java    # 黑名单管理
├── dto/                            # 请求对象
├── entity/                         # 9 张表实体
├── enums/                          # 枚举 (EventType, FreezeStatus, ExchangeStatus)
├── mapper/                         # MyBatis Plus Mapper (含原子 SQL)
├── scheduled/                      # 定时任务 (过期/解冻/月度重置)
└── service/                        # 业务逻辑
    ├── impl/PointsEventServiceImpl  # 积分事件核心处理
    ├── impl/BenefitServiceImpl      # 权益兑换 (防并发超兑)
    ├── impl/RuleEngineImpl          # 规则引擎 (等级倍率/活动加倍/月度上限)
    ├── impl/PointsFreezeServiceImpl # 积分冻结/解冻/结算
    └── impl/RuleServiceImpl         # 规则版本管理
```

## 数据库表设计 (9 张表)

| 表名 | 说明 | 核心设计 |
|------|------|---------|
| `member_level` | 会员等级 | 5 级体系, 含积分获取倍率和兑换折扣 |
| `points_account` | 积分账户 | 可用/冻结/累计获得/消费/过期, 月度已获取计数 |
| `points_flow` | 积分流水 | 幂等 event_id 唯一索引, 记录变动前后值 |
| `points_freeze` | 积分冻结 | 冻结单号唯一, 支持自动过期解冻 |
| `points_rule` | 积分规则 | 规则编码+版本号, JSON 格式规则值 |
| `benefit` | 权益商品 | 积分成本, 库存, 等级门槛, 日/总兑换上限 |
| `exchange_record` | 兑换记录 | 兑换单号唯一, 关联业务订单 |
| `blacklist` | 黑名单 | 支持临时拉黑 (过期自动解除) |
| `audit_log` | 审计日志 | 操作前后值对比, 操作人和 IP |

## 核心设计

### 防并发超兑

```
兑换流程:
1. 黑名单检查
2. 幂等检查 (eventId 去重)
3. 获取 Redisson 分布式锁 (lock:benefit:redeem:{memberId})
4. 校验: 权益状态 → 有效期 → 库存 → 账户积分 → 等级门槛 → 日限 → 总限
5. @Transactional 内原子执行:
   - accountMapper.deductPoints()   -- SQL WHERE available_points >= cost
   - benefitMapper.decrementStock() -- SQL WHERE available_stock > 0
   → 任一失败则整体回滚
6. 创建兑换记录 + 积分流水
```

### 幂等处理

- `points_flow.event_id` 唯一索引保证数据库层幂等
- 服务层双重检查: 锁前检查 + 锁内再检查
- 重复请求直接返回已有流水

### 规则引擎 (计算管道)

```
事件类型匹配规则 → 基础积分
    → 活动加倍 (activityCode 匹配时 ×multiplier)
    → 等级倍率 (会员 earnRate)
    → 月度上限 (min(积分, cap - 本月已获取))
```

### 规则版本管理

更新规则时: 旧版本 status 置为 0 → 创建新版本 (version+1) → 清除 Redis 缓存

### 缓存策略

| 缓存 Key | 内容 | TTL | 失效时机 |
|----------|------|-----|---------|
| `cache:points:rules` | 活跃规则列表 | 10 分钟 | 规则更新时主动删除 |
| `blacklist:{memberId}` | 黑名单状态 | 5 分钟 | 加/解锁时主动删除 |

## REST API

### 账户
| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/account/{memberId}` | 查询/自动创建积分账户 |
| GET | `/api/account/{memberId}/flows?page=1&size=20&eventType=` | 分页查询积分流水 |

### 积分
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/points/event` | 积分事件 (注册/消费/签到/活动) |
| POST | `/api/points/adjust` | 人工调整 (正数加/负数减) |
| POST | `/api/points/refund` | 退款返还积分 |
| POST | `/api/points/freeze` | 冻结积分 |
| POST | `/api/points/freeze/{freezeNo}/unfreeze` | 解冻积分 |
| POST | `/api/points/freeze/{freezeNo}/settle` | 结算冻结积分 |

### 权益
| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/benefit/list?memberId=` | 查询可用权益 |
| POST | `/api/benefit/redeem` | 兑换权益 |
| POST | `/api/benefit/refund?bizOrderNo=&eventId=&operator=` | 权益退款 |

### 规则
| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/rule/list` | 查询所有规则 |
| POST | `/api/rule/create` | 创建规则 |
| PUT | `/api/rule/update` | 更新规则 (自动创建新版本) |

### 黑名单
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/blacklist/add?memberId=&reason=&operator=` | 加入黑名单 |
| POST | `/api/blacklist/remove?memberId=&operator=` | 移除黑名单 |
| GET | `/api/blacklist/check/{memberId}` | 检查黑名单状态 |

## 请求示例

### 注册赠送积分
```json
POST /api/points/event
{
    "eventId": "reg-20260610-1001",
    "eventType": "REGISTER",
    "memberId": 1001,
    "remark": "新用户注册"
}
```

### 消费返积分
```json
POST /api/points/event
{
    "eventId": "purchase-ORD20260610001",
    "eventType": "PURCHASE",
    "memberId": 1001,
    "amount": 299,
    "bizOrderNo": "ORD20260610001"
}
```

### 每日签到
```json
POST /api/points/event
{
    "eventId": "checkin-1001-20260610",
    "eventType": "CHECKIN",
    "memberId": 1001
}
```

### 兑换权益
```json
POST /api/benefit/redeem
{
    "eventId": "redeem-1001-500coupon-20260610",
    "memberId": 1001,
    "benefitId": 1,
    "bizOrderNo": "EX20260610001"
}
```

### 人工调整
```json
POST /api/points/adjust
{
    "memberId": 1001,
    "points": -50,
    "eventId": "adjust-1001-20260610-001",
    "operator": "admin",
    "reason": "客诉补偿扣减"
}
```

### 退款返还
```json
POST /api/points/refund
{
    "bizOrderNo": "ORD20260610001",
    "eventId": "refund-ORD20260610001",
    "memberId": 1001,
    "refundAmount": 100,
    "operator": "admin"
}
```

## 定时任务

| Cron | 任务 | 说明 |
|------|------|------|
| `0 0 2 * * ?` | expirePoints | 每日 2AM 过期到期积分 |
| `0 */10 * * * ?` | autoUnfreezeExpired | 每 10 分钟自动解冻到期冻结记录 |
| `0 0 1 1 * ?` | resetMonthlyEarned | 每月 1 日凌晨重置月度获取计数 |

## 本地运行

### 1. 环境准备

- JDK 17+
- MySQL 8.0+
- Redis 6+
- Maven 3.8+

### 2. 初始化数据库

```bash
mysql -u root -p < src/main/resources/schema.sql
```

### 3. 修改配置

编辑 `src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/points_system?...
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379
```

### 4. 编译运行

```bash
# 编译
mvn clean compile

# 运行测试 (15 个单元测试)
mvn test

# 启动
mvn spring-boot:run
```

### 5. 验证

```bash
# 查询/创建账户
curl http://localhost:8080/api/account/1001

# 注册赠送积分
curl -X POST http://localhost:8080/api/points/event \
  -H "Content-Type: application/json" \
  -d '{"eventId":"reg-1001","eventType":"REGISTER","memberId":1001}'

# 消费返积分
curl -X POST http://localhost:8080/api/points/event \
  -H "Content-Type: application/json" \
  -d '{"eventId":"purchase-001","eventType":"PURCHASE","memberId":1001,"amount":299,"bizOrderNo":"ORD001"}'

# 查看流水
curl http://localhost:8080/api/account/1001/flows

# 兑换权益
curl -X POST http://localhost:8080/api/benefit/redeem \
  -H "Content-Type: application/json" \
  -d '{"eventId":"redeem-001","memberId":1001,"benefitId":1}'

# 查看规则
curl http://localhost:8080/api/rule/list

# 检查黑名单
curl http://localhost:8080/api/blacklist/check/1001
```

## 测试覆盖

| 测试类 | 用例数 | 覆盖场景 |
|--------|--------|---------|
| PointsEventServiceTest | 6 | 注册赠送、重复幂等、黑名单拦截、人工加/减积分、退款 |
| BenefitServiceTest | 5 | 兑换成功、积分不足、库存耗尽回滚、黑名单拦截、每日上限 |
| RuleEngineTest | 4 | 注册积分、消费倍率、月度上限、最低消费门槛 |

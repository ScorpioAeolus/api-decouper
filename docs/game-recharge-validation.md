# 游戏充值案例验证

案例位于 `api-decouper-starter-samples/api-decouper-sample-payment`。不修改 starter 的业务无关接口。

## 业务约定

固定服务端商品 `COINS_1000`：10.01 CNY 兑换 1000 游戏币。创建订单不扣款、不发币；渠道受理也不发币。只有主动查单得到 PAID，或发起/恢复结果明确为 PAID，且订单号、金额、币种匹配后，才能入账。

`GameRechargeService.createRecharge` 在同一事务内创建任务和充值订单，提交后消息唤醒；消息不可用时由扫描补偿。`GAME_RECHARGE:v1` Handler 根据持久化输入处理执行、核实和结果应用，消息和扫描无需保存回调方法名。

```text
本地事务：任务 READY + 充值单 NEW
提交后：MQ / 数据库扫描 → CAS → HTTP 发起支付
  ACCEPTED → 任务 DONE，充值单 ACCEPTED，余额不变
  PAID     → 任务 DONE + 充值单 PAID + 入账流水 + 钱包增加（同事务）
  DECLINED → 任务 DONE，充值单 FAILED，余额不变
  超时     → UNKNOWN → 主动查单 → 明确结果 / MANUAL
渠道通知：仅触发主动查单 → 核验结果 → 同事务入账
```

业务订单行锁串行化入账，流水以订单号唯一，钱包更新与流水、订单状态一起提交。任务结果回调还与任务 DONE 一起提交。重复通知不重复发币，迟到受理结果不覆盖 PAID。任务锁与业务锁按任务→订单的顺序获取。

## 已覆盖的 15 个场景

| 场景 | 验证结果/不变量 |
| --- | --- |
| 正常提交、MQ 发起、确认到账 | 提交前不调用渠道；受理不发币；确认后仅入账一次 |
| 本地外层事务回滚 | 无任务、无订单、无消息、无渠道调用 |
| MQ 不可用 | 周期扫描完成发起；仅一次渠道创建 |
| HTTP 读超时，重建执行器后恢复 | 查单核实，不重新发起支付 |
| 本地结果应用失败 | 业务更新回滚；只补应用已落库结果 |
| 重复消息与独立补偿并发 | CAS 竞争下仅一次渠道创建 |
| 到账通知早于发起结果 | 保持 PAID，不被迟到受理覆盖、不重复发币 |
| 渠道明确拒绝 | 订单 FAILED，钱包不变 |
| 未分类异常且查询不到 | 进入 MANUAL，不盲目重试 |
| 明确未受理且允许重试 | 使用原幂等键重试，受理后仍不发币 |
| 到账金额不匹配 | 拒绝入账 |
| 重复创建充值单 | 同请求返回同任务；更换玩家拒绝 |
| 超时且通知丢失，查单已支付 | 直接恢复入账，无需等待另一条通知 |
| 入账后的本地回调故障 | 钱包、流水、订单和任务一起回滚；补应用后入账一次 |
| 两个确认通知并发 | 一次入账、一次幂等返回 |

## 运行与证据

配置根 README 的专用 MySQL 环境变量后：

```bash
bash scripts/mysql-test.sh -pl api-decouper-starter-samples/api-decouper-sample-payment -am
# 全项目并行验证
bash scripts/mysql-test.sh -T2
```

查看 `api-decouper-starter-samples/api-decouper-sample-payment/target/failsafe-reports/TEST-com.api.decouper.game.GameRechargeScenarioIT.xml`，包含每个场景的执行结果。测试会重建 `game_wallet`、`game_recharge_order`、`game_credit_ledger` 及公共任务表，只能使用专用测试库。

本案例使用真实 MySQL、真实本地 HTTP 请求/读超时，以及测试内渠道服务器；消息用内存队列模拟。周期扫描用更短间隔加速测试，组件默认仍为 5 秒。重建执行器模拟运行时恢复，并非操作系统强杀进程。游戏测试隔离 JVM 全局 SPI 容器，仍经过真实 InfeedHandlerResolver；原 PaymentScenarioIT 验证真实 infeed 容器注册。

尚未验证真实 MQ broker、真实渠道签名/鉴权、线上回调入口、真实资金交易或操作系统进程崩溃。HTTP 示例协议的 `NOT_ACCEPTED_RETRYABLE` 仅为模拟渠道明确未受理的约定；不能直接套用任何真实渠道的 HTTP 状态码。没有默认公开通知接口，宿主须完成身份认证后触发核实。

# 支付案例

`src/main/java` 包含 PaymentExample：BigDecimal 金额、稳定外部幂等键、查询核实和本地订单状态条件更新。

`PaymentScenarioIT` 验证：发起受理不会误记 PAID；最终支付通知先到时，迟到的受理结果不覆盖终态。案例使用真实 infeed-spi 容器，网关采用本地实现，不调用真实支付渠道。

数据库环境配置及模块选择命令见根 README。

游戏充值案例位于 `examples/game`：同事务充值单与任务、真实 HTTP 渠道调用、超时查单、到账核验、幂等钱包入账，以及 15 个真实 MySQL 场景。详见 [游戏充值验证](../../docs/game-recharge-validation.md)。渠道与 MQ 为本地模拟，不涉及真实扣款。

# 支付案例

`src/main/java` 包含 PaymentExample：BigDecimal 金额、稳定外部幂等键、查询核实和本地订单状态条件更新。

`PaymentScenarioIT` 验证：发起受理不会误记 PAID；最终支付通知先到时，迟到的受理结果不覆盖终态。案例使用真实 infeed-spi 容器，网关采用本地实现，不调用真实支付渠道。

数据库环境配置及模块选择命令见根 README。

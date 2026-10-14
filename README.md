# api-decouper

一个嵌入业务应用的通用异步解耦组件。业务数据与任务表同事务提交，以 MQ 加速执行、以多实例每 5 秒数据库扫描兜底，通过 infeed-spi 扩展业务，通过 CAS 和执行令牌协调竞争。

原始框架保留在本地 `main`（`db18c8e`）；实现位于 `feature/reliable-local-tasks`。当前 SPI 和表结构替换了未完成的原型契约，不能直接覆盖旧表上线。

## 运行边界

- Java 8、Spring Boot 2.7、MySQL 8 / InnoDB；使用 Spring JDBC 的 `DataSourceTransactionManager`。
- 业务表与任务表必须同数据源、同事务管理器；组件拒绝无事务、只读事务或绑定错误数据源的提交。
- 外部调用在事务外运行；HTTP 连接、读取和总调用超时由业务网关实现配置。租约不是 HTTP 取消机制。
- 本地结果应用必须同步、只做短数据库操作；禁止 `@Async`、`REQUIRES_NEW`、远程调用或吞掉失败。
- 组件不承诺外部操作恰好一次。进程退出、超时或结果落库失败后，由业务核实外部事实；安全重发要求外部幂等或足够的业务证据。
- “任务完成”表示确定结果已应用本地，不等于支付成功。发起受理与最终到账是不同业务状态。

## 接入

1. 引入本项目构建的 JAR，以及宿主自己的 JDBC 驱动和连接池。
2. 由宿主按发布流程执行 `src/main/resources/db/mysql/V1__local_task.sql`。组件不会自动建表，也不提供旧原型表的自动迁移。
3. 显式启用并绑定数据源。所有时间以数据库生成的 UTC epoch 毫秒存储，不依赖 JVM 时区。

```yaml
api-decouper:
  enabled: true
  data-source-bean: dataSource
  transaction-manager-bean: transactionManager
  poll-interval-millis: 5000
  concurrency: 8
  lease-millis: 30000
  batch-size: 100
  max-scan-tasks: 1000
  scan-budget-millis: 1000
  max-execute-attempts: 5
  max-recover-attempts: 20
  max-apply-attempts: 20
  max-task-age-millis: 86400000
  recovery-delay-millis: 5000
  backoff-millis: 1000
  max-backoff-millis: 60000
```

`data-source-bean` 与 `transaction-manager-bean` 是具体 Bean 名称；必须指向同一个数据源。`scheduling-enabled: false` 可关闭自动轮询/监控，用于宿主自己调用 `scan()` 和 `evaluate()`。每个实例均可扫描，数据库 CAS 决定执行者，无需 Redis 锁或单一调度主节点。

### 业务扩展

```java
@BizSpi(name = "PAYMENT_INIT:v1")
public class PaymentInitHandler implements TaskHandler<PaymentRequest, PaymentReply> {
    public ExecutionResult<PaymentReply> execute(TaskContext ctx, PaymentRequest request)
            throws Exception {
        // operationKey 在全部尝试中固定，按网关协议作为外部幂等键。
        return ExecutionResult.success(gateway.initiate(ctx.getOperationKey(), request));
    }
    public RecoveryDecision<PaymentReply> recover(TaskContext ctx, PaymentRequest request)
            throws Exception {
        // 查询只是示意；应按外部协议分类，查询不到不能直接推断可以重发。
        return RecoveryDecision.manual("QUERY_REQUIRED");
    }
    public void applyResult(TaskContext ctx, PaymentRequest request,
                            ExecutionResult<PaymentReply> result) {
        // 同库条件更新，前置状态检查，终态不得被迟到结果覆盖。
    }
    public TaskCodec<PaymentRequest, PaymentReply> codec() {
        return codec; // 显式类型 JSON 编解码，验证输入/结果版本，不启用任意类名反序列化。
    }
}
```

完整可运行示例位于 [PaymentExample.java](src/test/java/com/api/decouper/examples/PaymentExample.java)，配套 [PaymentScenarioIT.java](src/test/java/com/api/decouper/PaymentScenarioIT.java)。示例采用 BigDecimal、明确外部结果分类，并保护 PAID 等终态，不会打包成宿主默认业务 Bean。

组件自动把 `@BizSpi` Handler 注册进真实 infeed-spi 容器并精确解析 `bizType + ":v" + handlerVersion`。不需要为本组件额外启用 infeed 广播代理。实现缺失、条件不满足和同名冲突明确报错；不允许多实现匹配后广播支付请求。

默认 infeed 容器是 JVM 全局容器；若其他 Spring 上下文已注册任务 Handler，启动会拒绝跨上下文路由。多上下文宿主应显式提供独立的 `ISpiContainer` Bean 或 `HandlerResolver`。旧任务未清理前，应保留对应的 Handler 版本；同名版本的业务语义须保持兼容。

### 提交与消息驱动

```java
@Transactional(transactionManager = "transactionManager")
public long createPaymentOrder(...) {
    // 创建订单；业务自身也必须保证订单创建幂等。
    return taskSubmissionService.submit(new TaskRequest(
        "PAYMENT_INIT", 1, orderId, "PAYMENT_INIT:" + orderId, payloadJson, 1));
}
```

`operationKey` 是区分业务操作的全局唯一幂等键。同键、同路由、同业务 ID、同输入版本及相同输入字符串返回已有任务 ID；不同输入拒绝，组件以 MANDATORY 事务边界标记失败回滚，即使调用方捕获提交冲突，外层事务也不能提交这次业务变更。若 JSON 键序可能变化，宿主提交前先规范化序列化。任务和幂等键不会自动删除。

宿主可选提供 `TaskMessagePublisher` Bean，发送仅包含 taskId 的唤醒消息。组件只在事务提交后调用它，发送失败由轮询兜底；发送器只负责发消息，不在原事务提交回调中同步执行任务。

MQ 消费时调用 `taskExecutorService.executeTask(taskId)`：DONE、ADVANCED、SKIPPED、NOT_FOUND 可确认消息，BUSY 建议退避重投；基础设施异常向外传播，适配器应请求重投。NOT_FOUND 建议宿主记录异常消息，但数据库任务仍为可靠事实来源。重复消息不会绕过任务状态、CAS 或下一次执行时间。

也可以完全不配置 MQ，单靠轮询完成任务。组件不绑定某一 MQ 产品；Kafka、RocketMQ 等发送/消费协议由宿主适配。

## 状态及重试

```text
READY / RETRY_WAIT -> EXECUTING -> RESULT_READY -> APPLYING -> DONE
                           |             ^           |
                           v             |           +-- 本地失败后只补结果应用
                        UNKNOWN -> RECOVERING
                                    |    |
                                    |    +-- 业务确认安全 -> RETRY_WAIT
                                    +-- 明确结果 / 继续等待 / MANUAL
```

- execute 返回 SUCCESS / FAILURE 为确定结果，先落库再应用本地；FAILURE 同样可最终完成任务。
- RETRY 必须给出时间和安全原因；UNKNOWN 和未分类异常进入核实。recover 默认 MANUAL。
- EXECUTING / RECOVERING 租约过期进入 UNKNOWN；APPLYING 租约过期只回 RESULT_READY。
- 执行令牌阻止旧执行者覆盖新状态。结果应用在短事务中锁定任务行、校验所有权，业务状态和 DONE 一起提交。
- 三阶段次数独立；超出预算进入 MANUAL。最大任务年龄限制新外部调用，已产生的外部效果仍可核实和应用。
- 本地结果失败采用指数退避及抖动；业务可返回外部重试时间，组件不会根据异常类型猜测重发安全性。
- `TaskRecoveryService.resume(id, expectedVersion, reason)` 仅供宿主鉴权后的运维入口调用：保留确定结果，无结果则先核实；开启新的核实/应用预算，不清空外部执行次数。事件记录操作原因。没有默认公开 HTTP 接口。

`local_task_event` 记录阶段变更、执行令牌、版本及结果分类。异常仅存稳定代码，不存原始请求、堆栈或密钥；业务提供的原因也应为脱敏稳定代码。输入和结果最大为 65535 字符。

## 监控和告警

`TaskMonitoringService.snapshot()` 返回低基数聚合指标；`RuntimeSignals` 提供 CAS 竞争、MQ 失败和扫描健康信号，可由宿主桥接现有指标系统。

内置规则：BACKLOG、OLD_PENDING、LEASE_EXPIRED、LONG_RUNNING、UNKNOWN_AGE、APPLY_FAILURES、MANUAL、BUDGET_EXHAUSTED、ROUTE_ERROR、MQ_UNAVAILABLE、SCAN_STALLED、WORKER_FAILURE。

```yaml
api-decouper:
  alert-threshold-millis: 60000  # 等待/未知状态和基础设施异常的时间阈值
  alert-window-millis: 10000    # 持续达到条件后告警
  alert-cooldown-millis: 300000 # 聚合去重后的重复提醒间隔
  backlog-threshold: 1000
  alert-count-thresholds:
    APPLY_FAILURES: 3
  disabled-alert-rules: []
```

通过 `TaskAlertSink` 接入宿主告警渠道；默认结构化日志，发送异常不影响任务状态。健康恢复后发送恢复事件；数据库查询失败仅更新已观测的基础设施规则，不把未观测的任务规则误判为恢复。LONG_RUNNING 独立于租约，持续续租的卡住任务仍可告警。CAS 正常竞争仅计数。告警在各实例内聚合；多实例部署可在宿主告警平台进一步去重。监控状态重启后重建，不承诺告警外部投递恰好一次。

生产建议对任务表建立保留、归档策略，监控查询和扫描索引成本；不要直接清除唯一幂等键。数据库不可用、执行容量耗尽和业务不可核实等情况会延长恢复时间，“5 秒”是默认轮询间隔，并非完成时间上限。

## 验证

```bash
mvn clean test
# 自动创建临时 MySQL 容器、等待就绪、运行 MySQL 测试并清理该容器：
bash scripts/mysql-test.sh
```

也可显式使用一个专用空测试库（测试会删除并重建 local_task、local_task_event，清空 sample_order）：

```bash
mvn -Pmysql-it verify -Dgpg.skip=true \
  '-Ddecouper.test.url=jdbc:mysql://127.0.0.1:13316/decouper_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
```

仅限测试凭据 root / decouper-test-only，不允许指向业务数据库。未启用 mysql-it 时不会运行 `*IT`；启用后连接失败会显式失败，不会静默跳过。验证不执行部署、数据库生产变更或远端推送。

设计与实现计划位于 `docs/superpowers/`，需求验收记录位于 `docs/acceptance.md`。

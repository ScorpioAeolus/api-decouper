# 第一版需求验收

对应用户定义：一款通用异步解耦组件，通过本地事务表与消息驱动实现本地事务与外部 API 可靠解耦；集成 5 秒分布式补偿、SPI 多业务扩展、CAS 竞争控制、业务感知重试和异常告警。

## 逐项证据

| 需求 | 实现 | 验证 |
| --- | --- | --- |
| 本地业务与外部 API 异步解耦 | TaskSubmissionService 在宿主事务中落表；TaskExecutorService 在事务外调用 API | TaskSubmissionIT、TaskExecutionIT |
| 本地事务表 + 消息驱动 | 提交后调用可选 TaskMessagePublisher；消费端按 taskId 统一触发 | TaskSubmissionIT 的提交前不发消息、发送失败任务仍存在；TaskExecutionIT 的重复触发与到期检查 |
| 每 5 秒分布式调度补偿 | 每个应用实例独立扫描，数据库 CAS 协调；有界线程池与总扫描预算 | TaskCompensationIT：无 MQ 完成、慢任务不阻塞扫描、跨阶段预算；LocalTaskRepositoryIT：并发 CAS |
| SPI 多业务扩展 | TaskHandler、infeed-spi 容器注册、精确 TYPE:vN 路由 | InfeedHandlerResolverTest：代理保留、缺失/重复/跨上下文拒绝；PaymentScenarioIT：实际发布 JAR 容器 |
| CAS 锁控资源竞争 | 版本抢占、唯一令牌完成校验、租约续期与到期恢复 | LocalTaskRepositoryIT、TaskExecutionIT：旧令牌、重复触发、回收与结果行锁竞争 |
| 业务感知重试 | ExecutionResult、RecoveryDecision；未知异常先核实；业务授权重试且受预算约束 | TaskContractTest、TaskExecutionIT：超时查询、安全重试到期、次数耗尽 |
| 外部结果通知业务 | Handler.applyResult；确定结果先落库，结果应用与 DONE 同事务 | TaskExecutionIT：应用失败业务回滚、只补应用、失去所有权整体回滚 |
| 丰富异常告警 | 12 类规则、窗口/阈值/冷却/禁用、恢复通知、自定义接收器 | TaskAlertMonitorTest、TaskMonitoringIT：去重恢复、接收器异常隔离、准确到期计数 |
| 支付业务正确性示例 | BigDecimal、外部幂等键、受理状态、条件更新防止覆盖终态 | PaymentScenarioIT；TaskExecutionIT 的外部结果持久化失败与本地失败恢复 |

## 已验证结果

2026-10-14，`mvn -Pmysql-it verify -Dgpg.skip=true`：13 个单元测试、31 个真实 MySQL 集成测试通过，无跳过。未进行生产部署、真实支付网关调用或某一 MQ 产品的网络故障演练。

## 保证范围

- 原子性要求同库同事务管理器，不覆盖跨库业务或回调中的外部副作用。
- MQ 是唤醒加速器；消息丢失由持久化任务扫描补偿。第一版提供 MQ 接口和消费入口，具体 MQ 客户端由宿主适配。
- 分布式补偿采用多实例轮询 + CAS，无单点调度锁；不是新建独立调度平台。
- 本地任务不会因 MQ 故障丢失；进程在 API 后结果落库前退出时，必须由业务查询、幂等协议或人工核实，无法单凭本地表判断外部事实。
- 定时补偿为最终推进提供执行机会，不保证外部不可用时仍能完成，五秒不是端到端 SLA。
- 规则及接收器可接入既有告警平台；默认日志，不包含实际短信、邮件或机器人凭据。

因此，此实现覆盖上述通用组件定义；生产接入仍需实现并验证具体业务 Handler、外部幂等/核实协议以及 MQ/告警适配器。

补充验证：周期调度能拾取首轮扫描之后新建的任务；两个独立执行器实例通过数据库 CAS 协调，外部操作只调用一次。

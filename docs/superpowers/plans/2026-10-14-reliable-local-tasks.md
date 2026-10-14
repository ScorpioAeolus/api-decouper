# Reliable Local Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现可嵌入宿主应用、支持 SPI 路由、MQ 加速和数据库补偿的可靠外部调用组件。

**Architecture:** JDBC 任务状态为可靠事实来源，MQ 和调度只触发统一执行器。执行器通过 infeed-spi 适配器解析唯一 Handler，将外部调用、未知结果核实及本地结果应用分开恢复。

**Tech Stack:** Java 8、Spring Boot 2.7.3、MySQL、infeed-spi 1.0.0.RELEASE、JUnit 5、Mockito、Docker CLI 隔离 MySQL 测试实例（执行时调整，避免新增测试运行库）。

**Spec:** docs/superpowers/specs/2026-10-14-api-decouper-design.md

## Global Constraints

- 业务表和任务表必须使用同一个数据源、同一个事务管理器。
- submit 必须在真实宿主事务中执行；外部 API 调用不得处于数据库事务内。
- execute、recover、applyResult 三个阶段独立；结果持久化后不得回到外部执行阶段。
- 所有任务所有权更新校验状态和 executionToken；数据库 UTC 决定到期与租约。
- 不确定结果默认核实或人工处理，不自动重发；MQ 适配可选。
- 同步应用本地结果；任务完成和业务状态更新在同一个短事务中提交。
- 保留原始 main 基线 db18c8e；仅在 feature/reliable-local-tasks 演进，不修改无关工具代码，不推送远端。

## Review Focus

- 相同 operationKey、不同输入：必须拒绝，而非静默返回旧任务（任务 2）。
- MQ 提前或重复触发：不得绕过 nextExecuteTime 或预算（任务 3、4）。
- 租约回收与 applyResult 并发：不得提交失去所有权的业务更新（任务 3）。
- 多 Spring 上下文与全局 SPI 容器：不得路由到另一个应用上下文中的实现（任务 1、5）。
- 数据源和事务管理器不匹配：在接入验证阶段失败，不产生假原子性（任务 2、5）。

## 文件与职责

所有 Java 路径相对 src/main/java/com/api/decouper；测试在 src/test/java/com/api/decouper 对应包。

- api/：TaskRequest、TaskContext、ExecutionResult、RecoveryDecision、TaskCodec、TaskMessagePublisher、TaskTriggerResult。
- spi/TaskHandler.java：业务扩展契约；spi/HandlerResolver.java：隔离 SPI 路由；spi/InfeedHandlerResolver.java：容器唯一解析。
- entity/LocalTask.java、entity/TaskStatus.java：持久化快照与状态编码。
- repository/LocalTaskRepository.java、repository/TaskEventRepository.java：参数化 SQL、CAS、事件及查询。
- core/TaskSubmissionService.java：事务内提交与提交后消息；core/TaskExecutorService.java：执行与恢复；core/TaskResultApplier.java：本地结果事务；core/TaskScheduler.java：分页扫描和容量控制。
- core/TaskRecoveryService.java：受控人工恢复；observability/：指标快照、告警规则与接收器。
- autoconfigure/：显式数据源绑定、配置校验、可选装配及线程池生命周期。
- src/main/resources/db/mysql/V1__local_task.sql：生产建表脚本；META-INF/spring.factories：Boot 2 自动配置入口。
- examples/ 与 README.md：宿主接入示例，禁止生产自动扫描示例 Handler。

## Task 1：业务契约与唯一 SPI 路由

**Interfaces:** `TaskHandler<P,R> extends ISpiProvider<TaskContext,Object>`，方法 `ExecutionResult<R> execute(TaskContext,P)`、`RecoveryDecision<R> recover(TaskContext,P)`、`void applyResult(TaskContext,P,ExecutionResult<R>)`、`TaskCodec<P,R> codec()`；`HandlerResolver.resolve(String bizType,int handlerVersion,TaskContext): TaskHandler<?,?>`。

- [ ] 写 TaskContractTest：成功、失败、重试、未知分别保留结果分类；recover 默认人工处理；重试时间不能为空；结果对象不可含混表达多个决策。
- [ ] 写 InfeedHandlerResolverTest：唯一命名目标命中；缺失、重复名、condition=false 均在副作用前拒绝；目标为 Spring 代理时仍调用代理；容器中外上下文对象不得被选中。
- [ ] 运行 `mvn -Dtest=TaskContractTest,InfeedHandlerResolverTest test`，确认缺少契约导致失败。
- [ ] 实现上述类型。容器用注入的 ISpiContainer，通过 lookup 选择精确配置名；bizType/version 到名称的映射启动校验。以宿主 Bean 身份校验隔离上下文，不修改全局默认容器、不使用广播代理调用。
- [ ] 以已发布 JAR 接口编译并重跑上述测试，全部通过后提交 `feat: define task contracts and infeed routing`。

## Task 2：事务提交、MySQL 仓储和审计

**Interfaces:** `TaskSubmissionService.submit(TaskRequest): long`；`LocalTaskRepository.findById(long): LocalTask`、`insert(TaskRequest): long`、`claim(long,int,TaskStatus,String): Optional<LocalTask>`、`transition(long,String,TaskStatus,TaskStatus): boolean`；实际结果/错误参数以类型化命令封装，禁止拼接任意 SQL。

- [ ] 增加 starter-test、MySQL 驱动测试依赖；MySQL 测试实例由 scripts/mysql-test.sh 创建并清理；测试类 *IT 通过 mysql-it profile 的 failsafe 执行，缺 Docker 时显式失败而非悄悄跳过。
- [ ] 写 TaskSubmissionIT：真实生成 ID；无事务拒绝；业务回滚同时回滚任务和事件；提交前不发 MQ；发送失败后任务仍存在；相同键同输入返回旧 ID、不同输入拒绝；并发重复插入只有一条；非绑定数据源事务拒绝。
- [ ] 写 LocalTaskRepositoryIT：MySQL UTC 到期校验；并发 CAS 仅一次成功；旧令牌不能更新；状态变更和事件共同回滚；结果持久化后拒绝退回 READY。
- [ ] 运行 `mvn -Pmysql-it -Dit.test=TaskSubmissionIT,LocalTaskRepositoryIT verify -Dgpg.skip=true`，确认新增行为失败。
- [ ] 实现任务/事件 DDL、唯一索引、仓储及 MANDATORY 提交服务；验证绑定资源为当前事务资源。提交后消息异常隔离。结果事务与外部调用不在此层混合。
- [ ] 重跑测试通过，提交 `feat: persist transactional tasks with fenced ownership`。

## Task 3：执行、核实和结果应用

**Interfaces:** `TaskExecutorService.executeTask(long): TaskTriggerResult`；`TaskResultApplier.apply(long,String executionToken): void`；返回值区分完成、已接管、跳过及基础设施失败，不把跳过误认业务成功。

- [ ] 写 TaskExecutionIT：未知异常进入 UNKNOWN；明确失败也进入结果应用；仅业务授权可重试；到期和预算共同校验；确定结果保存失败不调 apply；回调失败只补 apply；三阶段独立计数；null/非法决策进入人工处理。
- [ ] 写 TaskExecutionIT：外部调用时无事务；业务更新与 DONE 原子回滚；回调抛异常保持结果；迟到执行者不写回；执行崩溃走 recover；APPLYING 崩溃只补结果；回收器与 apply 的行锁竞争不会提交旧所有者业务更新。
- [ ] 运行 `mvn -Dtest=TaskExecutionIT test` 和 `mvn -Pmysql-it -Dit.test=TaskExecutionIT verify -Dgpg.skip=true`，确认失败。
- [ ] 实现执行器、分阶段预算和本地结果事务。结果应用先 SELECT FOR UPDATE 核验令牌，任一完成 CAS 失败必须抛异常回滚；业务执行期间不持有外部阶段数据库事务。
- [ ] 测试通过后提交 `feat: recover uncertain calls and apply durable results atomically`。

## Task 4：MQ 触发、扫描和有界容量

**Interfaces:** `TaskMessagePublisher.publish(long taskId): void`；`TaskScheduler.scan(): void`；`LocalTaskRepository.findDue(TaskStatus,Instant,long,int): List<LocalTask>`；过期回收使用独立查询和 CAS，不在扫描线程调用 API。

- [ ] 写 TaskSchedulerTest：默认 5000ms；单轮条数/时间预算；各状态独立稳定分页；满容量不抢占；停止时停止扫描并释放资源；重复消息和未到期消息不增加尝试次数。
- [ ] 写 TaskCompensationIT：禁用发送器仍可完成；消息发送失败仍可完成；MQ 与扫描竞争只调用一次；旧扫描快照不能回收已续租任务；慢任务不阻塞其他状态扫描。
- [ ] 先运行对应单测和 mysql-it 验证失败，再实现有界线程池、容量许可、扫描游标、租约续期与回收；手动及 MQ 调用均复用入口校验。
- [ ] 重跑上述测试通过，提交 `feat: add bounded scheduling and optional mq wakeups`。

## Task 5：自动配置、告警与受控人工恢复

**Interfaces:** `TaskAlertSink.accept(TaskAlert): void`；`TaskAlertMonitor.evaluate(): void`；`TaskRecoveryService.resume(long id,int expectedVersion,String reason): boolean`，仅恢复原阶段且不把 UNKNOWN 直接变为外部重试。

- [ ] 写 DecouperAutoConfigurationTest：缺配置不隐式启用；数据源/事务歧义启动失败；SPI 全局容器含外上下文实现启动拒绝；无需 MQ 可装配；示例不成为宿主 Bean；线程池正确关闭。
- [ ] 写 TaskAlertMonitorTest：积压、最老等待、租约、未知、结果应用、预算、路由、MQ 与扫描无进展规则；聚合、冷却、恢复通知；接收器异常隔离；指标不带 taskId 高基数标签。
- [ ] 写 TaskRecoveryServiceIT：版本冲突、空原因、终态、非法跳阶段均拒绝；合法恢复有审计且保留确定结果。
- [ ] 先运行测试确认失败，实现配置属性、Boot 2 注册入口、规则引擎与人工恢复 API（不提供默认 HTTP 端点）；再重跑通过并提交 `feat: configure task runtime and operational alerts`。

## Task 6：接入示例和整体验证

- [ ] 将 PaymentTaskHandler 移出生产自动扫描；写同库订单示例，使用 BigDecimal、稳定 operationKey、查询核实及前置状态条件更新。README 包含事务要求、建表方式、MQ ACK 语义、版本演进和恢复限制。
- [ ] 写 PaymentScenarioIT：发起受理只更新 ACCEPTED；支付通知先到达终态后，迟到 apply 不覆盖终态；外部成功本地更新暂时失败时只调用外部一次。
- [ ] 执行 `mvn clean test` 与 `mvn -Pmysql-it verify -Dgpg.skip=true`；记录真实测试数量及结果，MySQL 测试不可用时明确保留未验证项，不能宣称可靠性已验证。
- [ ] 检查 `git diff main --check`、自动配置产物及 README 示例；逐条核对设计约束，修复发现的问题后提交 `docs: demonstrate reliable payment initiation and recovery`。

## 当前证据与执行交接

- 2026-10-14：原始框架 `mvn -o test` BUILD SUCCESS，无测试用例。
- 已创建本地 main 基线及 feature/reliable-local-tasks 分支，未配置远端。
- 已用 javap 验证发布 JAR 提供 ISpiContainer.lookup 和 SpiRegister 注册 API；适配行为仍需任务 1 测试证明。
- 当前 Java 为 1.8.0_202，docker CLI 存在；MySQL / Docker 已通过隔离实例测试验证。
- 用户已确认边界并授权当前会话直接实现；原型互相依赖的前三阶段合为一个核心提交，后续完整回归验证。


## 实际完成记录

- 用户已授权全部技术细节由实现者决定，业务语义调整才需再次确认。
- 任务 1-4 的原型替换核心合并为提交 2c6a8de；任务 5-6 与最终审查修复在后续提交中保存。
- 精确测试清单、需求对应和技术取舍以 docs/acceptance.md、docs/implementation-notes.md 为准；MySQL 集成覆盖替代了计划中的部分独立 mock 单测。
- 独立审查三项 P2 已用回归修复，未合并或推送分支。

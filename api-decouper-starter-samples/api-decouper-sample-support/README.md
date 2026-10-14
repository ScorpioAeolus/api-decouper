# 案例测试支持

只提供其他案例复用的测试基础设施，不属于 starter 运行时。

- MySqlTestSettings：从环境读取连接信息；不提供默认账号或默认库；重建表前要求显式 ALLOW_RESET。
- MySqlFixture：连接已经存在的专用测试库，初始化任务表和示例订单表，提供 protected JDBC/事务/仓储字段。
- TestDatabaseLease：同机跨测试 JVM 串行保护同一库的初始化及测试生命周期，不属于组件运行时的锁机制。
- 环境变量：DECOUPER_TEST_JDBC_URL、DECOUPER_TEST_USERNAME、DECOUPER_TEST_PASSWORD、DECOUPER_TEST_ALLOW_RESET。

禁止在这里加入某一种支付或 MQ 产品的专用逻辑。新案例若需要专属表，应在其自身测试初始化中扩展。

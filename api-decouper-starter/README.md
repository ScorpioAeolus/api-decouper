# api-decouper-starter

组件运行时模块，包含 API/SPI、JDBC 仓储、任务执行与补偿、自动配置和告警。

- Maven 坐标：`io.github.scorpioaeolus:api-decouper-starter:1.0-0.RELEASE`。
- 自动配置：`src/main/resources/META-INF/spring.factories`。
- 建表脚本：`src/main/resources/db/mysql/V1__local_task.sql`。
- 只运行本模块及父项目单元测试：在仓库根执行 `mvn -pl api-decouper-starter -am clean test`。

完整接入方式与事务约束见根 README。此模块不依赖 samples、不包含示例 Handler 或数据库测试凭据。

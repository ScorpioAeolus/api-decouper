# api-decouper-starter-samples

这是案例聚合模块，不是可部署应用或组件运行时依赖。每个案例以独立 Maven 子模块维护自己的源码、资源、测试和说明。

| 模块 | 职责 |
| --- | --- |
| api-decouper-sample-support | 公共 JDBC 测试基座、显式测试库配置校验；只连接现有 MySQL |
| api-decouper-sample-payment | 可编译的支付 Handler 示例及受理/终态验证 |
| api-decouper-sample-reliability | 事务原子性、CAS、租约、调度、故障恢复、告警验证 |

新增案例步骤：

1. 新建 `api-decouper-sample-<dimension>` 目录，以本目录 POM 为父 POM。
2. 将模块加入本目录 POM 的 modules，并把 artifactId 加到根 POM 的 Central `excludeArtifacts`；生产示例依赖 `api-decouper-starter`。
3. 数据库案例测试按 test scope 依赖 `api-decouper-sample-support`，继承 `com.api.decouper.samples.support.MySqlFixture`；普通单元测试无需测试库。
4. 为新维度编写 `*IT` 及本模块 README。避免依赖其他案例的内部测试类。
5. 设置根 README 中的数据库环境变量，运行 `bash scripts/mysql-test.sh -pl api-decouper-starter-samples/<module> -am`。

可继续扩展真实 MQ 适配、多业务路由、应用重启、限流与容量等案例。当前不为空目录创建无实现模块。

父模块统一管理版本；根 POM 使用 Central 插件的 excludeArtifacts 显式排除全部样例 artifactId，samples 另保留 maven.deploy.skip/gpg.skip。新增样例时同步更新排除列表，不能只依赖 Maven deploy 的 skip 属性。

同一台机器上，公共基座通过 JVM 内锁与本地文件锁串行保护同一个测试库的测试生命周期，所以支付/可靠性模块可用 -T 并行构建。跨机器并行应配置不同数据库。

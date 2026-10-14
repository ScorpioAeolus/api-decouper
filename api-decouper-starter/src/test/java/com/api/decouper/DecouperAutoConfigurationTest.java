package com.api.decouper;

import static org.assertj.core.api.Assertions.*;

import com.api.decouper.autoconfigure.*;
import com.api.decouper.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.*;

class DecouperAutoConfigurationTest {
  ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DecouperAutoConfiguration.class));

  @Test
  void absentFlagDoesNotEnableRuntime() {
    runner.run(c -> assertThat(c).doesNotHaveBean(TaskExecutorService.class));
  }

  @Test
  void explicitMatchingDatasourceAllowsNoMq() {
    runner
        .withPropertyValues("api-decouper.enabled=true", "api-decouper.scheduling-enabled=false")
        .withBean("dataSource", DriverManagerDataSource.class, () -> new DriverManagerDataSource())
        .withBean(
            "transactionManager",
            DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(new DriverManagerDataSource()))
        .run(
            c ->
                assertThat(c)
                    .hasFailed()); // valid manager bound to a different datasource must fail at
    // startup
  }

  @Test
  void matchingBeansStartWithoutOpeningDatabaseWhenSchedulingDisabled() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    runner
        .withPropertyValues("api-decouper.enabled=true", "api-decouper.scheduling-enabled=false")
        .withBean("dataSource", DriverManagerDataSource.class, () -> ds)
        .withBean(
            "transactionManager",
            DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(ds))
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).hasSingleBean(TaskSubmissionService.class);
              assertThat(c).hasSingleBean(TaskScheduler.class);
            });
  }
}

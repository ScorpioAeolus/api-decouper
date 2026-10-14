package com.api.decouper.samples.support;

import com.api.decouper.repository.LocalTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

public abstract class MySqlFixture {
  protected DriverManagerDataSource ds;
  protected JdbcTemplate jdbc;
  protected DataSourceTransactionManager tm;
  protected TransactionTemplate tx;
  protected LocalTaskRepository repo;

  protected MySqlTestSettings settings;
  private TestDatabaseLease databaseLease;

  @BeforeEach
  protected void database() throws java.io.IOException {
    settings = MySqlTestSettings.from(System.getenv());
    databaseLease = TestDatabaseLease.acquire(settings.getUrl());
    try {
      ds =
          new DriverManagerDataSource(
              settings.getUrl(), settings.getUsername(), settings.getPassword());
      jdbc = new JdbcTemplate(ds);
      tm = new DataSourceTransactionManager(ds);
      tx = new TransactionTemplate(tm);
      jdbc.execute("DROP TABLE IF EXISTS local_task_event");
      jdbc.execute("DROP TABLE IF EXISTS local_task");
      new ResourceDatabasePopulator(new ClassPathResource("db/mysql/V1__local_task.sql"))
          .execute(ds);
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS sample_order(id VARCHAR(80) PRIMARY KEY,status VARCHAR(30))");
      jdbc.update("DELETE FROM sample_order");
      repo = new LocalTaskRepository(jdbc, tm);
    } catch (RuntimeException | Error ex) {
      try {
        databaseLease.close();
      } catch (java.io.IOException cleanup) {
        ex.addSuppressed(cleanup);
      }
      throw ex;
    }
  }

  @AfterEach
  protected void releaseDatabase() throws java.io.IOException {
    if (databaseLease != null) databaseLease.close();
  }
}

package com.api.decouper;

import com.api.decouper.repository.LocalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

abstract class MySqlFixture {
  DriverManagerDataSource ds;
  JdbcTemplate jdbc;
  DataSourceTransactionManager tm;
  TransactionTemplate tx;
  LocalTaskRepository repo;

  @BeforeEach
  void database() {
    ds =
        new DriverManagerDataSource(
            System.getProperty(
                "decouper.test.url",
                "jdbc:mysql://127.0.0.1:13316/decouper_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
            "root",
            "decouper-test-only");
    jdbc = new JdbcTemplate(ds);
    tm = new DataSourceTransactionManager(ds);
    tx = new TransactionTemplate(tm);
    jdbc.execute("DROP TABLE IF EXISTS local_task_event");
    jdbc.execute("DROP TABLE IF EXISTS local_task");
    new ResourceDatabasePopulator(new ClassPathResource("db/mysql/V1__local_task.sql")).execute(ds);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS sample_order(id VARCHAR(80) PRIMARY KEY,status VARCHAR(30))");
    jdbc.update("DELETE FROM sample_order");
    repo = new LocalTaskRepository(jdbc, tm);
  }
}

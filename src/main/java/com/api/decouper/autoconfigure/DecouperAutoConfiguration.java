package com.api.decouper.autoconfigure;

import com.api.decouper.api.*;
import com.api.decouper.core.*;
import com.api.decouper.observability.*;
import com.api.decouper.repository.*;
import com.api.decouper.spi.*;
import com.infeed.spi.annotation.BizSpi;
import com.infeed.spi.core.container.ISpiContainer;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter({
  DataSourceAutoConfiguration.class,
  DataSourceTransactionManagerAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "api-decouper", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DecouperProperties.class)
public class DecouperAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public RuntimeSignals decouperSignals() {
    return new RuntimeSignals();
  }

  @Bean
  @ConditionalOnMissingBean
  public LocalTaskRepository localTaskRepository(ApplicationContext context, DecouperProperties p) {
    p.validate();
    DataSource ds = context.getBean(p.getDataSourceBean(), DataSource.class);
    DataSourceTransactionManager manager =
        context.getBean(p.getTransactionManagerBean(), DataSourceTransactionManager.class);
    return new LocalTaskRepository(new JdbcTemplate(ds), manager);
  }

  @Bean
  @ConditionalOnMissingBean
  public ISpiContainer decouperSpiContainer() {
    return ISpiContainer.DEFAULT_INSTANCE;
  }

  @Bean
  @ConditionalOnMissingBean(HandlerResolver.class)
  public HandlerResolver decouperHandlerResolver(
      ApplicationContext context, ISpiContainer container) {
    List<TaskHandler<?, ?>> handlers = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Object bean : context.getBeansWithAnnotation(BizSpi.class).values())
      if (bean instanceof TaskHandler) {
        BizSpi ann =
            org.springframework.aop.support.AopUtils.getTargetClass(bean)
                .getAnnotation(BizSpi.class);
        if (!names.add(ann.name()))
          throw new IllegalStateException("Duplicate task SPI: " + ann.name());
        handlers.add((TaskHandler<?, ?>) bean);
      }
    synchronized (container) {
      for (TaskHandler<?, ?> h : handlers) container.addToContainer(TaskHandler.class, h);
      return new InfeedHandlerResolver(container, handlers);
    }
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskSubmissionService taskSubmissionService(
      LocalTaskRepository r, ObjectProvider<TaskMessagePublisher> publisher, RuntimeSignals s) {
    return new TaskSubmissionService(r, publisher.getIfAvailable(), s);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public TaskExecutorService taskExecutorService(
      LocalTaskRepository r, HandlerResolver h, DecouperProperties p, RuntimeSignals s) {
    return new TaskExecutorService(r, h, p, s);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean
  public TaskScheduler taskScheduler(
      LocalTaskRepository r, TaskExecutorService e, DecouperProperties p, RuntimeSignals s) {
    return new TaskScheduler(r, e, p, s);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskRecoveryService taskRecoveryService(LocalTaskRepository r) {
    return new TaskRecoveryService(r);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskAlertSink taskAlertSink() {
    return a ->
        LoggerFactory.getLogger("api-decouper.alert")
            .warn(
                "rule={} recovered={} count={} observedAt={}",
                a.getRule(),
                a.isRecovered(),
                a.getCount(),
                a.getObservedAt());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskAlertMonitor taskAlertMonitor(TaskAlertSink sink, DecouperProperties p) {
    return new TaskAlertMonitor(sink, p, System::currentTimeMillis);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean
  public TaskMonitoringService taskMonitoringService(
      LocalTaskRepository r, TaskAlertMonitor m, DecouperProperties p, RuntimeSignals s) {
    return new TaskMonitoringService(r, m, p, s);
  }
}

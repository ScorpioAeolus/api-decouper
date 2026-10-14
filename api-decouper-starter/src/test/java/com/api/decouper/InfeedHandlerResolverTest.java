package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.api.decouper.api.*;
import com.api.decouper.spi.*;
import com.infeed.spi.annotation.BizSpi;
import com.infeed.spi.core.container.ISpiContainer;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

class InfeedHandlerResolverTest {
  @BizSpi(name = "PAY:v1")
  static class Handler implements TaskHandler<String, String> {
    boolean active = true;

    public boolean condition(TaskContext c) {
      return active;
    }

    public ExecutionResult<String> execute(TaskContext c, String p) {
      return ExecutionResult.success(p);
    }

    public void applyResult(TaskContext c, String p, ExecutionResult<String> r) {}

    public TaskCodec<String, String> codec() {
      return TaskCodec.strings();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  ISpiContainer container(TaskHandler bean) {
    ISpiContainer c = mock(ISpiContainer.class);
    doReturn(Collections.singletonMap("PAY:v1", bean)).when(c).lookup(TaskHandler.class);
    return c;
  }

  @Test
  void resolvesUniqueSpringProxyAndKeepsDefaultRecoverySafe() throws Exception {
    Handler target = new Handler();
    ProxyFactory f = new ProxyFactory(target);
    TaskHandler<?, ?> proxy = (TaskHandler<?, ?>) f.getProxy();
    InfeedHandlerResolver r =
        new InfeedHandlerResolver(container(proxy), Collections.singletonList(proxy));
    assertSame(proxy, r.resolve("PAY", 1, new TaskContext(1, "PAY", 1, "b", "op", 1, 1, 0, 0)));
    assertEquals(RecoveryDecision.Kind.MANUAL, target.recover(null, "{}").getKind());
  }

  @Test
  void rejectsForeignContextAndDuplicateNamesBeforeCallingBusiness() {
    Handler own = new Handler(), foreign = new Handler();
    assertThrows(
        IllegalStateException.class,
        () -> new InfeedHandlerResolver(container(foreign), Collections.singletonList(own)));
    assertThrows(
        IllegalStateException.class,
        () -> new InfeedHandlerResolver(container(own), Arrays.asList(own, new Handler())));
  }

  @Test
  void missingAndConditionFalseAreErrors() {
    Handler h = new Handler();
    h.active = false;
    InfeedHandlerResolver r = new InfeedHandlerResolver(container(h), Collections.singletonList(h));
    assertThrows(IllegalStateException.class, () -> r.resolve("OTHER", 1, null));
    assertThrows(IllegalStateException.class, () -> r.resolve("PAY", 1, null));
  }
}

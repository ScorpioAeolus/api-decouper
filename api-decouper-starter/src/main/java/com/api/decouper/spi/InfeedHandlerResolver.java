package com.api.decouper.spi;

import com.api.decouper.api.TaskContext;
import com.infeed.spi.annotation.BizSpi;
import com.infeed.spi.api.ISpiProvider;
import com.infeed.spi.core.container.ISpiContainer;
import java.util.*;
import org.springframework.aop.support.AopUtils;

/** Resolves exactly one registered Spring bean. Never invokes infeed's multi-provider proxy. */
public final class InfeedHandlerResolver implements HandlerResolver {
  private final Map<String, TaskHandler<?, ?>> handlers;

  public InfeedHandlerResolver(
      ISpiContainer container, Collection<? extends TaskHandler<?, ?>> beans) {
    Map<String, TaskHandler<?, ?>> map = new HashMap<>();
    for (TaskHandler<?, ?> b : beans) {
      BizSpi ann = AopUtils.getTargetClass(b).getAnnotation(BizSpi.class);
      if (ann == null || !ann.name().matches("[A-Za-z0-9_.-]+:v[1-9][0-9]*"))
        throw new IllegalStateException("Handler requires @BizSpi(name=TYPE:vN)");
      if (map.put(ann.name(), b) != null)
        throw new IllegalStateException("Duplicate handler: " + ann.name());
    }
    Map<String, ? extends ISpiProvider> registered = container.lookup(TaskHandler.class);
    for (Map.Entry<String, TaskHandler<?, ?>> e : map.entrySet()) {
      if (registered == null || registered.get(e.getKey()) != e.getValue())
        throw new IllegalStateException(
            "SPI registration missing or belongs to another context: " + e.getKey());
    }
    if (registered != null)
      for (Map.Entry<String, ? extends ISpiProvider> e : registered.entrySet())
        if (map.get(e.getKey()) != e.getValue())
          throw new IllegalStateException("Foreign SPI context: " + e.getKey());
    handlers = Collections.unmodifiableMap(map);
  }

  public TaskHandler<?, ?> resolve(String type, int version, TaskContext context) {
    TaskHandler<?, ?> h = handlers.get(type + ":v" + version);
    if (h == null || !h.condition(context))
      throw new IllegalStateException("No applicable handler for " + type + ":v" + version);
    return h;
  }
}

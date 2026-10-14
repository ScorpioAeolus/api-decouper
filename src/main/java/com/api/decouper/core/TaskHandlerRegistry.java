package com.api.decouper.core;

import com.api.decouper.spi.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that holds all available TaskHandlers.
 * It discovers beans implementing TaskHandler and maps them by their bizType.
 */
@Slf4j
@Component
public class TaskHandlerRegistry implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    private final Map<String, TaskHandler> handlerMap = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Initialize the registry by scanning for TaskHandler beans.
     */
    @PostConstruct
    public void init() {
        Map<String, TaskHandler> beans = applicationContext.getBeansOfType(TaskHandler.class);
        if (beans == null || beans.isEmpty()) {
            log.warn("No TaskHandlers found in the context.");
            return;
        }

        beans.values().forEach(handler -> {
            String bizType = handler.getBizType();
            if (handlerMap.containsKey(bizType)) {
                throw new IllegalStateException("Duplicate TaskHandler found for bizType: " + bizType);
            }
            handlerMap.put(bizType, handler);
            log.info("Registered TaskHandler for bizType: {}", bizType);
        });
    }

    /**
     * Retrieve a handler by bizType.
     * @param bizType The business type key.
     * @return The handler or null if not found.
     */
    public TaskHandler getHandler(String bizType) {
        return handlerMap.get(bizType);
    }
}

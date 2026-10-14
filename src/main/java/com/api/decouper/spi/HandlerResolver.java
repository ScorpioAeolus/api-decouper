package com.api.decouper.spi;
import com.api.decouper.api.TaskContext;
public interface HandlerResolver { TaskHandler<?,?> resolve(String type,int version,TaskContext context); }

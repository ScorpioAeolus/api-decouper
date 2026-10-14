package com.api.decouper.api;
@FunctionalInterface public interface TaskMessagePublisher { void publish(long taskId) throws Exception; }

package com.api.decouper.example;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.api.decouper.entity.LocalTask;
import com.api.decouper.spi.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentTaskHandler implements TaskHandler {

    @Override
    public String getBizType() {
        return "PAYMENT_INIT";
    }

    @Override
    public boolean execute(LocalTask task) throws Exception {
        log.info("Executing Payment Task: {}", task.getId());
        
        // 1. Parse Payload
        JSONObject params = JSON.parseObject(task.getPayload());
        String orderId = params.getString("orderId");
        Double amount = params.getDouble("amount");

        // 2. Call External API (Simulated)
        log.info("Calling External Payment Gateway for Order {} Amount {}", orderId, amount);
        // Simulate network call...
        // Thread.sleep(100);
        
        // Simulate success
        return true;
    }

    @Override
    public void onSuccess(LocalTask task) {
        log.info("Payment Task {} Success. Updating Order Status...", task.getId());
        // Call local service to update order status to PAID
    }

    @Override
    public void onFail(LocalTask task, Exception e) {
        log.error("Payment Task {} Failed. Cause: {}", task.getId(), e.getMessage());
        // Maybe mark order as PAYMENT_FAILED if it's a hard failure
    }
}

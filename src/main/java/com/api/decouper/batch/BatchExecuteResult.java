package com.api.decouper.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量处理结果
 *
 * @author typhoon
 * @date 2025-10-21 16:43 Tuesday
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public  class BatchExecuteResult {

    private int success;
    private int failed;
    private int total;

    /**
     * 分配结果码,可能一批执行了一半,无法继续执行
     */
    private Integer resultCode;

    public static final BatchExecuteResult emptyResult() {
        return BatchExecuteResult.builder()
                .success(0)
                .failed(0)
                .total(0)
                .resultCode(0)
                .build();
    }

}

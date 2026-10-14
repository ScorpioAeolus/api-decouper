package com.api.decouper.batch;

import com.api.decouper.util.CollectionUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 批量执行抽象定义
 *
 * @author typhoon
 * @date 2025-11-26 16:59 Wednesday
 */
@Slf4j
public abstract class AbstractBatchExecute<T extends IAutoIncrId,P> {


    /**
     * 批处理核心入口方法
     *
     * @param retrieve
     * @param currentTime
     * @return
     */
    protected BatchExecuteResult handle(IRetrieveData<T> retrieve,Long idStart,P param, long currentTime, String operator) {
        List<T> list = retrieve.query(idStart);
        if(CollectionUtil.isEmpty(list)) {
            log.info("AbstractBatchExecute.handle ,there is no data to handle,idStart={}",idStart);
            return BatchExecuteResult.emptyResult();
        }
        int success = 0;
        int failed = 0;
        int total = 0;
        while(CollectionUtil.isNotEmpty(list)) {
            BatchExecuteResult result = this.doBatchExecute(list,param,operator,currentTime);
            log.info("AbstractBatchExecute.handle  batch execute data success;idStart={},result={}",idStart,result);
            success += result.getSuccess();
            failed += result.getFailed();
            total += result.getTotal();
            idStart = list.get(list.size() - 1).getId();

            list = retrieve.query(idStart);
        }
        log.info("AbstractBatchExecute.handle end;success={},failed={},total={}", success, failed, total);
        return BatchExecuteResult.builder()
                .failed(failed)
                .success(success)
                .total(total)
                .build();
    }

    /**
     * 批量执行抽象逻辑,最终调用doExecute交给子类去实现,典型的模板方法
     *
     * @param list
     * @param operator
     * @param currentTime
     * @return
     */
    protected  BatchExecuteResult doBatchExecute(List<T> list,P param, String operator,long currentTime) {
        if(CollectionUtil.isEmpty(list)) {
            return BatchExecuteResult.emptyResult();
        }
        int total = list.size();
        int success = 0;
        int failed = 0;
        for (T t : list) {
            try {
                if(!checkValid(t)) {
                    log.info("AbstractBatchExecute.doBatchExecute meta data not match condition;data={},operator={}",t,operator);
                    failed ++;
                    continue;
                }
                log.info("AbstractBatchExecute.doBatchExecute;data={},operator={}",t,operator);
                this.doExecute(t,param,operator,currentTime);
                log.info("AbstractBatchExecute.doBatchExecute execute one meta data end;data={},operator={}",t,operator);
                success++;
            } catch (Exception e) {
                log.error("AbstractBatchExecute.doBatchExecute occur error;data={},operator={}", t,operator, e);
                failed ++;
            }
        }
//        try {
//            // 休眠 1 秒
//            TimeUnit.SECONDS.sleep(1);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            log.error("AbstractBatchExecute.doBatchExecute thread is interrupted...",e);
//        }
        return BatchExecuteResult.builder()
                .failed(failed)
                .success(success)
                .total(total)
                .build();
    }

    /**
     * 检查数据状态有效性
     *
     * @description <ul>
     *     <li>比如针对使用调度任务实现状态流转,任务先开始执行并捞取到了核算单,但是在执行逻辑之前被人工触发,
     *         ,那么这个时候数据状态已经发生变更,执行逻辑的时候需要过滤掉这部分数据</li>
     * </ul>
     * @param t
     * @return
     */
     public abstract  boolean checkValid(T t);

    /**
     * 真正的逻辑执行抽象,交给子类去实现
     *
     * @param t
     * @param operator
     * @param currentTime
     */
    public abstract  void doExecute(T t,P param,String operator,long currentTime);
}

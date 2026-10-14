package com.api.decouper.batch;

import java.util.List;

/**
 * 获取数据函数接口
 *
 * @author typhoon
 * @since 2025-11-26 16:57 Wednesday
 *
 * @param <T>
 */
@FunctionalInterface
public interface IRetrieveData<T>{

    List<T> query(Long idStart);
}

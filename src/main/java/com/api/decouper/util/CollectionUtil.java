package com.api.decouper.util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 集合工具类
 *
 *
 * @author typhoon
 * @since 2025-12-18 15:45 Thursday
 */
public class CollectionUtil {


    public static <T> boolean isEmpty(Collection<T> c) {
        if(null == c || c.isEmpty()) {
            return true;
        }
        return false;
    }

    public static <T> boolean isNotEmpty(Collection<T> c) {
        return !isEmpty(c);
    }

    public static <T> int sizeOf(Collection<T> c) {
        if(isEmpty(c)) {
            return 0;
        }
        return c.size();
    }


    /**
     * 获取两个集合的差集
     *
     * <ul>
     *     <li>source集合减去target中重复的元素</li>
     * </ul>
     *
     * @author typhoon
     * @param source
     * @param target
     * @param <T>
     * @return
     */
    public static final <T> Collection<T> diffSet(Collection<T> source,Collection<T> target) {
        if(isEmpty(source)) {
            return Collections.emptyList();
        }
        if(isEmpty(target)) {
            return source;
        }
        return source.stream()
                .filter(item -> !target.contains(item))
                .collect(Collectors.toSet());
    }

    /**
     * 获取两个集合的交集
     *
     * @author typhoon
     * @param source
     * @param target
     * @param <T>
     * @return
     */
    public static final <T> Collection<T> intersect(Collection<T> source,Collection<T> target) {
        if(isEmpty(source) || isEmpty(target)) {
            return Collections.emptyList();
        }
        return source.stream()
                .filter(item -> target.contains(item))
                .collect(Collectors.toSet());
    }


    /**
     * 获取两个集合的并集s
     *
     *
     * @author typhoon
     * @param source
     * @param target
     * @param <T>
     * @return
     */
    public static final <T> Collection<T> union(Collection<T> source,Collection<T> target) {
        if(isEmpty(source)) {
            return target;
        }
        if(isEmpty(target)) {
            return source;
        }
        Set<T> set = new HashSet<>();
        set.addAll(source);
        set.addAll(target);
        return set;
    }

    public static final <T> List<T> emptyList() {
        return Collections.emptyList();
    }


    public static final <S,T> List<T> convert(List<S> sList,IConvert<S,T> iConvert) {
        if(isEmpty(sList)) {
            return emptyList();
        }
        return sList.stream()
                .map(iConvert::convert)
                .collect(Collectors.toList());
    }

    @FunctionalInterface
    public static interface IConvert<S,T> {
        T convert(S s);
    }
}

package com.api.decouper.repository;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Date;

@Slf4j
public class ApiCallLocalTransRepository {

    private static final String table_name = " api_call_local_transation ";
    private static final String insert = " INSERT INTO ";
    private static final String left_bracket = " ( ";
    private static final String right_bracket = " ) ";
    private static final String values = " VALUES ";
    private static final String delimiter = ",";

    @Resource
    private JdbcTemplate jdbcTemplate;

//    public int insert(IdWorkerSeqDO record) {
//        if (null == jdbcTemplate) {
//            throw new RuntimeException("未注入JdbcTemplate");
//        }
//        if(null == record.getCreateTime()) {
//            record.setCreateTime(new Date());
//        }
//        StringBuilder builder = new StringBuilder();
//        builder.append(insert)
//                .append(table_name)
//                .append(values)
//                .append(left_bracket)
//                .append(record.getHostId())
//                .append(delimiter)
//                .append(record.getCreateTime())
//                .append(right_bracket);
//        KeyHolder keyHolder = new GeneratedKeyHolder();
//
//        jdbcTemplate.update(connection -> {
//            PreparedStatement ps = connection.prepareStatement(builder.toString(), Statement.RETURN_GENERATED_KEYS);
//            return ps;
//        }, keyHolder);
//        return keyHolder.getKey().intValue();
//
//    }
    public int deleteByPrimaryKey(Long id) {
        if (null == jdbcTemplate) {
            throw new RuntimeException("您选择了DB作为中心化节点,但是没有注入JdbcTemplate");
        }
        return 0;
    }
}
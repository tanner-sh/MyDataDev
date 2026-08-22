package com.example.dbadmin.dto;

import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体绑定。
 *
 * <p>{@link ConnectionRequest} 除了 record 的规范构造器，还留了一个「不带连接档案字段」的
 * 兼容构造器。多构造器的 record 在反序列化时选错构造器会静默丢字段 —— 接口照样返回 200，
 * 存下来的值却全是 null。这里把「请求体里的每个字段都要绑上」钉死，往后再加字段也不会漏。</p>
 */
class ConnectionRequestBindingTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new ParameterNamesModule());

    @Test
    void bindsEveryConnectionProfileFieldFromJson() throws Exception {
        String json = """
                {
                  "name": "订单主库",
                  "dbType": "mysql",
                  "jdbcUrl": "jdbc:mysql://host:3306/shop",
                  "username": "app",
                  "password": "secret",
                  "environment": "prod",
                  "readonly": false,
                  "groupName": "订单业务",
                  "tags": "核心,只读",
                  "defaultSchema": "shop",
                  "initSql": "SET time_zone = '+08:00'",
                  "description": "张三负责"
                }
                """;

        ConnectionRequest request = mapper.readValue(json, ConnectionRequest.class);

        assertThat(request.name()).isEqualTo("订单主库");
        assertThat(request.groupName()).isEqualTo("订单业务");
        assertThat(request.tags()).isEqualTo("核心,只读");
        assertThat(request.defaultSchema()).isEqualTo("shop");
        assertThat(request.initSql()).isEqualTo("SET time_zone = '+08:00'");
        assertThat(request.description()).isEqualTo("张三负责");
    }

    @Test
    void stillAcceptsARequestBodyWithoutTheProfileFields() throws Exception {
        String json = """
                {"name":"本地库","dbType":"h2","jdbcUrl":"jdbc:h2:mem:t","username":"sa","password":"","environment":"dev","readonly":false}
                """;

        ConnectionRequest request = mapper.readValue(json, ConnectionRequest.class);

        assertThat(request.name()).isEqualTo("本地库");
        assertThat(request.groupName()).isNull();
        assertThat(request.tags()).isNull();
    }
}

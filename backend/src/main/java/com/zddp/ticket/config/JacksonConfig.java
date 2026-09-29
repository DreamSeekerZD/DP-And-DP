package com.zddp.ticket.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * JSON 序列化规则。
 *
 * <p>只注册包装类型 {@code Long}，<b>刻意不注册原始类型 {@code long}</b>：
 * <ul>
 *   <li>包装 Long 在本项目里一律是 BIGINT 编号（用户编号、演出编号、发布者编号），
 *       按契约序列化成十进制字符串，避免 JavaScript 安全整数范围截断。</li>
 *   <li>原始 long 只用于计数（例如分页 total），必须保持 JSON 数字，
 *       不能被误转成字符串。</li>
 * </ul>
 * 因此新增字段时：编号用 Long，计数用原始 long 或 int。
 * {@code priceCent}、{@code totalStock} 等金额与库存是 Integer，保持数字。
 *
 * <p>时间与时区规则在 application.yml 里配置（spring.jackson.time-zone=UTC），
 * 使 OffsetDateTime 统一输出为 UTC 的 Z 形式。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigintIdAsStringCustomizer() {
        return new Jackson2ObjectMapperBuilderCustomizer() {
            @Override
            public void customize(Jackson2ObjectMapperBuilder builder) {
                builder.serializerByType(Long.class, ToStringSerializer.instance);
            }
        };
    }
}

package com.wherelee.cabinet.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化的两条全局约定。
 *
 * <p><b>1）ID（包装类型 {@code Long}）一律输出成字符串。</b>
 * 主键用雪花 ID（{@code id-type: assign_id}），19 位十进制已经超出 JS 的安全整数
 * 2^53-1（约 9.0e15）。前端拿到 {@code 1948123456789012345} 会静默变成
 * {@code 1948123456789012300}，然后按错误 ID 去请求——排查时数据看起来"莫名其妙对不上"。
 * 所以后端直接发字符串，前端不参与数值解释。
 *
 * <p><b>只对 {@code Long.class} 生效，不能捎上 {@code Long.TYPE}（实测教训）。</b>
 * 分页的 total/pages、token 的 expiresIn 这些计数器声明为原始 {@code long}，
 * 连它们一起转字符串后，前端拿到的是 {@code "total":"1"}：TS 类型写的是 number、
 * Element Plus 的 {@code :total} 也期望数字，契约说谎。于是定下写法：
 * <b>ID 字段用 {@code Long}（走字符串），计数与时长用 {@code long}（保持数字）。</b>
 * 更根本的保护是"ID 用字符串类型声明"，所以前端契约里 id 一律是 string。
 *
 * <p><b>2）时间统一 {@code yyyy-MM-dd HH:mm:ss}。</b>
 * 默认会输出 ISO 带纳秒的串（{@code 2026-10-07T17:18:53.732421700}），两端各自截串
 * 是后期扯皮的常见来源，底座一次性定死。
 *
 * <p>注意这里用 {@code serializerByType} 而不是 {@code builder.modules(...)}：
 * 后者会<b>替换</b>掉 Boot 自动注册的模块（Jdk8Module、ParameterNamesModule 等），
 * 造成 Optional 或构造器参数名解析在角落里坏掉。
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer cabinetJacksonCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            // 故意不注册 Long.TYPE：原始 long 是计数与时长，必须是 JSON 数字（见类注释）
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            builder.serializerByType(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
            builder.deserializerByType(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
        };
    }
}

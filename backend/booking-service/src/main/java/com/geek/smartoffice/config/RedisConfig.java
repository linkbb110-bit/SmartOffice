package com.geek.smartoffice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

/**
 * 分别提供 Redisson 客户端和 Spring Data RedisTemplate：前者用于房间锁、布隆过滤器，
 * 后者按指定序列化方式读写缓存。两者共用 Redis 连接配置，但各自管理连接与数据操作。
 */
@Configuration
public class RedisConfig {

    /**
     * 从 Spring 的 RedisProperties 读取单节点地址、数据库与可选密码，创建 Redisson Bean。
     * 建连和命令超时限制故障时的等待时间，关闭客户端内部命令重试；调用失败如何降级由业务层决定。
     * 容器销毁 Bean 时调用 shutdown 释放连接，避免后台线程与连接池持续占用资源。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        // 创建 Bean 时延迟建立连接，首次实际使用 Redisson 时才访问 Redis。
        // 启动期布隆初始化也会触发首次使用，因此延迟连接并不保证启动阶段完全不访问 Redis。
        config.setLazyInitialization(true);
        var server = config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase())
                .setConnectTimeout(1000)
                .setTimeout(1500)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        // 此密码用于 Redis 服务自身的连接认证，与 HTTP 用户的 JWT 身份认证是不同边界。
        if (StringUtils.hasText(properties.getPassword())) {
            server.setPassword(properties.getPassword());
        }
        return Redisson.create(config);
    }

    /**
     * 把连接工厂与缓存序列化策略组合为可注入的 RedisTemplate。
     * 普通键和 Hash 字段使用字符串，普通值和 Hash 值使用 JSON，读取时需与写入格式配套。
     * 这里新建的 ObjectMapper 仅配置本模板的 Redis 数据格式，不改动 MVC 或消息使用的映射器。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // JavaTimeModule 让缓存值能够处理 LocalDateTime 等 Java 时间类型；
        // 禁用时间戳输出后写为 JSON 时间文本，避免落成数字或数组形式。
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        // 手动构造模板后完成初始化检查，返回时连接工厂和四类序列化器已设置完毕。
        template.afterPropertiesSet();
        return template;
    }
}

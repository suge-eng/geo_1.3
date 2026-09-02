package com.geo.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * 【MyBatis-Plus配置】
 *
 * 设计思路：
 * 配置两个核心功能：
 *
 * 1. 分页插件（PaginationInnerInterceptor）：
 *    数据库不分页的话数据量大会查崩。service里调用selectPage时，插件会自动在SQL后加LIMIT。
 *    指定DbType.MYSQL因为我们用的是MySQL，不同数据库分页语法不一样。
 *
 * 2. 字段自动填充（MetaObjectHandler）：
 *    每张表都有createdAt和updatedAt两个字段，代码里每次手动set很容易忘。
 *    配置这个后：
 *    - INSERT时自动塞 createdAt = now()，updatedAt = now()
 *    - UPDATE时自动塞 updatedAt = now()
 *    配合实体类上的 @TableField(fill = FieldFill.INSERT) 注解使用。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus拦截器：注册MySQL分页插件。
     * 这样service层调用 page(new Page<>(1,10), queryWrapper) 时自动分页。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 实体字段自动填充处理器。
     * 给所有带 @TableField(fill=...) 注解的字段自动填值，
     * 这样entity不用手动set创建/更新时间。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                // 插入时：创建时间和更新时间都填当前时间
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // 更新时：只更新updatedAt
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
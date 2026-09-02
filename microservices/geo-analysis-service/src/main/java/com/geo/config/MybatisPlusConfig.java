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
 * 【MyBatis-Plus配置 - ORM增强】
 * 设计思路：
 * 1. 分页插件（PaginationInnerInterceptor）：
 *    - MyBatis-Plus的IPage分页方法依赖这个拦截器，否则物理分页不生效（会返回全部数据再内存分页）
 *    - 绑定MySQL方言，针对SQL优化
 * 2. 元数据自动填充（MetaObjectHandler）：
 *    - 避免在业务代码中重复写"setCreatedAt(now)、setUpdatedAt(now)"
 *    - 新增时自动填createdAt和updatedAt；更新时自动填updatedAt
 *    - 要求实体字段上加@TableField(fill=INSERT)或@TableField(fill=INSERT_UPDATE)才会生效
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
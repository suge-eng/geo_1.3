package com.geo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.geo.mapper")
@EnableAsync
public class GeoRpaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoRpaServiceApplication.class, args);
    }
}

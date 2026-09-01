package com.geo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.geo.mapper")
@EnableAsync
@EnableScheduling
public class GeoRpaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoRpaServiceApplication.class, args);
    }
}

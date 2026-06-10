package com.example.points;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.points.mapper")
@EnableScheduling
public class PointsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PointsApplication.class, args);
    }
}

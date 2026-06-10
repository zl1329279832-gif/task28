package com.membership.points;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.membership.points.mapper")
@EnableScheduling
public class MembershipPointsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipPointsApplication.class, args);
    }
}

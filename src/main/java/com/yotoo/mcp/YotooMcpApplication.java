package com.yotoo.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YotooMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(YotooMcpApplication.class, args);
    }

}

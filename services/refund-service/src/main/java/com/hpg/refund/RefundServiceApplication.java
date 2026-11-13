package com.hpg.refund;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RefundServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefundServiceApplication.class, args);
    }
}

package com.arnavjot.glimpse.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GlimpseServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlimpseServerApplication.class, args);
    }
}

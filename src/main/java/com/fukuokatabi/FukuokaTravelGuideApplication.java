package com.fukuokatabi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FukuokaTravelGuideApplication {
    public static void main(String[] args) {
        SpringApplication.run(FukuokaTravelGuideApplication.class, args);
    }
}

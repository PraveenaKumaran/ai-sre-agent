package com.aisre.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point.
 *
 * {@code @SpringBootApplication} tells Spring Boot to start up, scan this
 * package (and below) for components/controllers, and launch the embedded
 * web server. Running {@code main} boots the whole service.
 *
 * {@code @ConfigurationPropertiesScan} tells Spring to find our
 * {@code @ConfigurationProperties} records (FoundryProperties, AgentProperties)
 * and bind the application.yml values into them automatically.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiSreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSreApplication.class, args);
    }
}

package com.aisre.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * {@code @SpringBootApplication} tells Spring Boot to start up, scan this
 * package (and below) for components/controllers, and launch the embedded
 * web server. Running {@code main} boots the whole service.
 */
@SpringBootApplication
public class AiSreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSreApplication.class, args);
    }
}

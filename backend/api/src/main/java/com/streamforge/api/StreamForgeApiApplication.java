package com.streamforge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StreamForgeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamForgeApiApplication.class, args);
    }
}

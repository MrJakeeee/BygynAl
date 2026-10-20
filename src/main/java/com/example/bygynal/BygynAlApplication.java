package com.example.bygynal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class BygynAlApplication {

    public static void main(String[] args) {
        SpringApplication.run(BygynAlApplication.class, args);
    }

}

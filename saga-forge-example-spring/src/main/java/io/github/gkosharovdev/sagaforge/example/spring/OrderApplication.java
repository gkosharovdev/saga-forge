package io.github.gkosharovdev.sagaforge.example.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The canonical order saga as a Spring Boot application. There is no Saga Forge assembly code: the starter finds the
 * saga, the handlers and the Spring Data repository in this package, builds the runtime over the application's
 * {@code DataSource} and starts it ({@code specs/008-spring-boot-starter} SC-001).
 */
@SpringBootApplication
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}

package io.github.gkosharovdev.sagaforge.example.jmolecules.application;

import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Payments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * The canonical order saga over a domain marked only with jMolecules ({@code
 * specs/016-marker-spi-jmolecules} US1). saga-forge-jmolecules on the class path makes the starter
 * recognise the domain's markers; there is no Saga Forge assembly code.
 */
@SpringBootApplication(scanBasePackages = "io.github.gkosharovdev.sagaforge.example.jmolecules")
public class JMoleculesOrderApplication {

  public static void main(String[] args) {
    SpringApplication.run(JMoleculesOrderApplication.class, args);
  }

  /** The domain's payment service, announcing its events through Spring. */
  @Bean
  Payments payments(ApplicationEventPublisher events) {
    return new Payments(events::publishEvent);
  }
}

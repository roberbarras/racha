package io.github.roberbarras.racha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Racha backend. Stores progress, settings, statistics and sync state; it never serves media content.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RachaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RachaApplication.class, args);
    }
}

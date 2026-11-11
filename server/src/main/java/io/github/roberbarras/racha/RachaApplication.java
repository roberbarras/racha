package io.github.roberbarras.racha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Racha backend. Stores progress, settings, statistics and sync state; it never serves media content.
 */
@SpringBootApplication
public class RachaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RachaApplication.class, args);
    }
}

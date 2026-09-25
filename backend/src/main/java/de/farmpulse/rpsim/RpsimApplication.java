package de.farmpulse.rpsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Backend of the FS25 AI role-play simulation: fact layer, file bridge and AI narration orchestration. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RpsimApplication {

    public static void main(String[] args) {
        SpringApplication.run(RpsimApplication.class, args);
    }
}

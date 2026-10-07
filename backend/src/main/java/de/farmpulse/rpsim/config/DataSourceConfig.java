package de.farmpulse.rpsim.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Technical review 10/2026, Phase 0.1 (S-1): the auto-configured Hikari pool, with the password from
 * {@link DatabaseCredentials} (Spring Boot reference "Configure a Custom DataSource"). Every other
 * {@code spring.datasource.*} / {@code spring.datasource.hikari.*} setting keeps working.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties, RpsimProperties rpsim) {
        String password = DatabaseCredentials.resolve(properties.determineUrl(), properties.determineUsername(),
                properties.determinePassword(), rpsim.getDb().getPasswordFile());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).password(password).build();
    }
}

package com.chivasrumberas;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.net.URI;
import java.util.Arrays;

@SpringBootApplication
public class ChivasRumberasApplication {

    public static final long PROCESS_START_NANOS = System.nanoTime();

    public static void main(String[] args) {
        SpringApplication.run(ChivasRumberasApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(
            @Value("${DATABASE_URL:jdbc:postgresql://localhost:5432/chivas_rumberas}") String rawUrl,
            @Value("${DB_USERNAME:chivas}") String defaultUser,
            @Value("${DB_PASSWORD:chivas_secret_2026}") String defaultPassword
    ) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);

        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("jdbc:postgresql://")) {
            config.setJdbcUrl(trimmed);
            config.setUsername(defaultUser);
            config.setPassword(defaultPassword);
        } else if (trimmed.startsWith("postgresql://") || trimmed.startsWith("postgres://")) {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
            config.setJdbcUrl(jdbcUrl);

            String userInfo = uri.getUserInfo();
            if (userInfo != null && userInfo.contains(":")) {
                String[] parts = userInfo.split(":", 2);
                config.setUsername(parts[0]);
                config.setPassword(parts[1]);
            } else {
                config.setUsername(defaultUser);
                config.setPassword(defaultPassword);
            }
        } else {
            config.setJdbcUrl(trimmed);
            config.setUsername(defaultUser);
            config.setPassword(defaultPassword);
        }
        return new HikariDataSource(config);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${app.cors.origins:*}") String rawOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] origins = Arrays.stream(rawOrigins.split(","))
                        .map(String::trim)
                        .map(s -> s.replaceAll("/+$", ""))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

                var reg = registry.addMapping("/**")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");

                if (origins.length == 0 || (origins.length == 1 && "*".equals(origins[0]))) {
                    reg.allowedOriginPatterns("*");
                } else {
                    reg.allowedOrigins(origins);
                }
            }
        };
    }
}

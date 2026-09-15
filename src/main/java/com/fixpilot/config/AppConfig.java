package com.fixpilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Responsibility (single):
 * - Build the two pre-authenticated RestClient beans (GitHub, Groq) that
 *   GitHubService and GroqService depend on. Auth headers live here, once,
 *   rather than in every method that calls out to either API.
 */
@Configuration
@EnableConfigurationProperties(FixPilotProperties.class)
public class AppConfig {

    @Bean
    public RestClient githubRestClient(FixPilotProperties props) {
        return RestClient.builder()
                .baseUrl(props.getGithub().getApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getGithub().getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Bean
    public RestClient groqRestClient(FixPilotProperties props) {
        return RestClient.builder()
                .baseUrl(props.getGroq().getApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getGroq().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

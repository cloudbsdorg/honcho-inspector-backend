package com.revytechinc.honchoinspector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(HonchoProperties.class)
public class HttpClientConfig {

    @Bean
    public RestClient honchoRestClient(
        @Value("${honcho.api-version:v3}") String apiVersion,
        @Value("${honcho.request-timeout-ms:30000}") long timeoutMs
    ) {
        return RestClient.builder()
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
            }})
            .build();
    }
}

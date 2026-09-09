package com.review.worker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires exactly one shared {@link HttpClient} (per the approved tech stack: plain, non-virtual-thread
 * blocking client) and two independently-timed {@link RestClient} beans built on top of it — one for the
 * Gateway (short timeout; Gateway calls must never be allowed to block as long as an LLM completion), one
 * for llama-server (long timeout; a single completion can legitimately take tens of minutes).
 *
 * <p>Sharing the underlying {@code HttpClient} (rather than building two separate ones) reuses the same
 * connection pool/selector infrastructure and matches the pattern already used by the Gateway's own
 * {@code RestClientConfig} for its outbound GitLab/llama clients.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    @Qualifier("gatewayRestClient")
    public RestClient gatewayRestClient(HttpClient sharedHttpClient, WorkerProperties properties) {
        ClientHttpRequestFactory requestFactory = jdkRequestFactory(
                sharedHttpClient, Duration.ofSeconds(properties.getNetwork().getGatewayTimeoutSec()));
        return RestClient.builder()
                .baseUrl(properties.getGateway().getUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + properties.getGateway().getApiKey())
                .build();
    }

    @Bean
    @Qualifier("llamaRestClient")
    public RestClient llamaRestClient(HttpClient sharedHttpClient, WorkerProperties properties) {
        ClientHttpRequestFactory requestFactory = jdkRequestFactory(
                sharedHttpClient, Duration.ofSeconds(properties.getNetwork().getRequestTimeoutSec()));
        return RestClient.builder()
                .baseUrl(properties.getLlama().getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private ClientHttpRequestFactory jdkRequestFactory(HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }
}

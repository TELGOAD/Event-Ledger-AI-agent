package com.eventledger.gateway.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import io.micrometer.tracing.Tracer;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(Tracer tracer) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);

        ClientHttpRequestInterceptor tracingInterceptor = (request, body, execution) -> {
            var span = tracer.currentSpan();
            if (span != null) {
                var context = span.context();
                request.getHeaders().set("X-Trace-Id", context.traceId());
                request.getHeaders().set("X-Span-Id", context.spanId());
                request.getHeaders().set("traceparent",
                    String.format("00-%s-%s-01", context.traceId(), context.spanId()));
            }
            return execution.execute(request, body);
        };

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(tracingInterceptor);
        return restTemplate;
    }
}

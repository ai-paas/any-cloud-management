package com.aipaas.anycloud.configuration.infrastructure;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "prometheus")
public class PrometheusMetricProperties {

    private Map<String, Map<String, String>> metrics;

    public Map<String, Map<String, String>> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Map<String, String>> metrics) {
        this.metrics = metrics;
    }
}

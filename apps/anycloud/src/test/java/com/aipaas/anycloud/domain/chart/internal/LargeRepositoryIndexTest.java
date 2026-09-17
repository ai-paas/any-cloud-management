package com.aipaas.anycloud.domain.chart.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.chart.api.response.ChartListResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 공개 헬름 저장소의 index.yaml 은 크다.
 *
 * <p>prometheus-community 가 6.4MB, bitnami 는 더 크다. snakeyaml 의 기본 상한이 3MiB 라
 * "The incoming YAML document exceeds the limit: 3145728 code points" 로 거부됐고, 화면에는
 * 그냥 차트가 없는 것처럼 보였다.
 */
class LargeRepositoryIndexTest extends AbstractUnitTest {

    private final ChartParser parser = new ChartParser();

    /** 실제 index.yaml 모양으로 목표 크기까지 항목을 늘린다. */
    private String indexYamlOfAtLeast(int bytes) {
        StringBuilder sb = new StringBuilder("apiVersion: v1\nentries:\n");
        for (int i = 0; sb.length() < bytes; i++) {
            sb.append("  chart-").append(i).append(":\n");
            sb.append("  - name: chart-").append(i).append('\n');
            sb.append("    version: 1.0.").append(i).append('\n');
            sb.append("    appVersion: \"1.0\"\n");
            sb.append("    description: ").append("x".repeat(200)).append('\n');
            sb.append("    urls:\n      - https://example.test/chart-")
                    .append(i)
                    .append(".tgz\n");
        }
        return sb.toString();
    }

    @Test
    void anIndexBiggerThanThreeMegabytesStillParses() {
        String index = indexYamlOfAtLeast(4 * 1024 * 1024);

        ChartListResponse parsed = parser.parseIndexYaml("prometheus-community", index);

        assertThat(parsed.getCharts()).isNotEmpty();
    }

    @Test
    void aSmallIndexIsUnaffected() {
        String index = "apiVersion: v1\n" + "entries:\n"
                + "  nginx:\n"
                + "  - name: nginx\n"
                + "    version: 1.2.3\n"
                + "    description: web server\n"
                + "    urls:\n      - https://example.test/nginx.tgz\n";

        ChartListResponse parsed = parser.parseIndexYaml("demo", index);

        assertThat(parsed.getCharts()).hasSize(1);
        assertThat(parsed.getCharts().get(0).getName()).isEqualTo("nginx");
    }
}

package io.aipaas.cluster.agent.rbac.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LabelSelectorTest {

	@Test
	void emptySelector_matchesAll() {
		assertThat(new LabelSelector(null).matches(Map.of("any", "x"))).isTrue();
		assertThat(new LabelSelector(Map.of()).matches(Map.of())).isTrue();
	}

	@Test
	void allKeysMustMatch() {
		var s = new LabelSelector(Map.of("anycloud.io/tier", "prod", "region", "us-west-1"));
		assertThat(s.matches(Map.of("anycloud.io/tier", "prod", "region", "us-west-1"))).isTrue();
		assertThat(s.matches(Map.of("anycloud.io/tier", "prod"))).isFalse();
		assertThat(s.matches(Map.of("anycloud.io/tier", "dev", "region", "us-west-1"))).isFalse();
	}

	@Test
	void nullClusterLabels_neverMatchesNonEmptySelector() {
		assertThat(new LabelSelector(Map.of("k", "v")).matches(null)).isFalse();
	}
}

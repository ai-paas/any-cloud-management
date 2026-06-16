package io.aipaas.cluster.provisioning.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.core.PulumiPreviewResult;
import org.junit.jupiter.api.Test;

class PulumiPreviewParserTest {

	private static final String SAMPLE = """
			{
			  "steps": [
			    {
			      "op": "create",
			      "urn": "urn:pulumi:anycloud-AWS-dev-c1::anycloud-k8s::aws:ec2/vpc:Vpc::c1-vpc",
			      "newState": {"type": "aws:ec2/vpc:Vpc"}
			    },
			    {
			      "op": "create",
			      "urn": "urn:pulumi:anycloud-AWS-dev-c1::anycloud-k8s::aws:ec2/instance:Instance::c1-master-0",
			      "newState": {"type": "aws:ec2/instance:Instance"}
			    },
			    {
			      "op": "same",
			      "urn": "urn:pulumi:anycloud-AWS-dev-c1::anycloud-k8s::pulumi:pulumi:Stack::anycloud-k8s-c1",
			      "oldState": {"type": "pulumi:pulumi:Stack"}
			    }
			  ],
			  "changeSummary": {"create": 2, "same": 1}
			}
			""";

	@Test
	void parse_extractsSummaryAndSteps() {
		PulumiPreviewResult result = PulumiPreviewParser.parse("stack-1", false, SAMPLE);

		assertThat(result.stackName()).isEqualTo("stack-1");
		assertThat(result.stackExistedBefore()).isFalse();
		assertThat(result.changeSummary()).containsEntry("create", 2).containsEntry("same", 1);
		assertThat(result.hasChanges()).isTrue();
		assertThat(result.steps()).hasSize(3);
		assertThat(result.steps().get(1).op()).isEqualTo("create");
		assertThat(result.steps().get(1).type()).isEqualTo("aws:ec2/instance:Instance");
		assertThat(result.steps().get(1).name()).isEqualTo("c1-master-0");
		// oldState 만 있는 step (same) 도 type 추출.
		assertThat(result.steps().get(2).type()).isEqualTo("pulumi:pulumi:Stack");
	}

	@Test
	void parse_sameOnly_meansNoChanges() {
		PulumiPreviewResult result = PulumiPreviewParser.parse("s", true,
				"{\"steps\": [], \"changeSummary\": {\"same\": 5}}");

		assertThat(result.hasChanges()).isFalse();
	}

	@Test
	void parse_malformedJson_returnsEmptyResultInsteadOfThrowing() {
		PulumiPreviewResult result = PulumiPreviewParser.parse("s", true, "not-json{{");

		assertThat(result.changeSummary()).isEmpty();
		assertThat(result.steps()).isEmpty();
		assertThat(result.hasChanges()).isFalse();
	}

	@Test
	void parse_blankStdout_returnsEmptyResult() {
		PulumiPreviewResult result = PulumiPreviewParser.parse("s", true, "  ");

		assertThat(result.changeSummary()).isEmpty();
		assertThat(result.steps()).isEmpty();
	}
}

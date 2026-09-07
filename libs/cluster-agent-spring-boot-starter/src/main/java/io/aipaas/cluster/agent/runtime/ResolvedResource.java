package io.aipaas.cluster.agent.runtime;

import java.util.List;

/**
 * Single-kind resolution 결과.
 *
 * @see KubeResourceService#resolveResource(String, String)
 */
public record ResolvedResource(
        String plural,
        String singular,
        String kind,
        String group,
        String version,
        boolean namespaced,
        List<String> shortNames) {}

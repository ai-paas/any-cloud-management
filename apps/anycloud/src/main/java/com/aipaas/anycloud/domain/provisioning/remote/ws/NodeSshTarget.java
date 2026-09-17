package com.aipaas.anycloud.domain.provisioning.remote.ws;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebSocket 경로에서 클러스터와 노드를 읽는다. */
public record NodeSshTarget(String vmName, String host) {

    private static final Pattern PATH = Pattern.compile("/v1/vms/([^/]+)/nodes/([^/]+)/ssh/?$");

    /** 읽지 못하면 null. 잘못된 경로로 세션을 여는 것보다 끊는 쪽이 낫다. */
    public static NodeSshTarget from(URI uri) {
        if (uri == null) {
            return null;
        }
        Matcher m = PATH.matcher(uri.getPath());
        if (!m.find()) {
            return null;
        }
        return new NodeSshTarget(decode(m.group(1)), decode(m.group(2)));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}

package com.aipaas.anycloud.domain.vmoptions;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vm-options")
public class VmOptionsProperties {

    private int defaultLimit = 50;
    private int maxLimit = 200;
}

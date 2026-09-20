package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import java.util.List;

/** CSP 마다 지금 통과하는 생성 요청 한 벌을 만들어 준다. */
public interface ProvisioningDefaultsService {

    List<ProvisioningDefaults> listDefaults();
}

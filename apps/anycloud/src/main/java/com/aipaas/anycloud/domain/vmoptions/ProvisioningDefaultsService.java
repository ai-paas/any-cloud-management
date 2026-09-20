package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import java.util.List;

/** CSP 마다 지금 통과하는 생성 요청 한 벌을 만들어 준다. */
public interface ProvisioningDefaultsService {

    /** provider 를 주면 그 CSP 만. 비우면 전부. */
    List<ProvisioningDefaults> listDefaults(String provider);
}

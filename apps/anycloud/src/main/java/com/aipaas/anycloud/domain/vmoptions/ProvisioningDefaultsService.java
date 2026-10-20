package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import com.aipaas.anycloud.domain.vmoptions.api.SpecFilter;
import java.util.List;

/** CSP 별로 현재 통과하는 생성 요청 구성. */
public interface ProvisioningDefaultsService {

    /** provider 를 주면 그 CSP 만. 비우면 전부. */
    List<ProvisioningDefaults> listDefaults(String provider);

    /** 조건 지정 시 해당 조건에 맞는 인스턴스 선택. */
    List<ProvisioningDefaults> listDefaults(String provider, SpecFilter filter);
}

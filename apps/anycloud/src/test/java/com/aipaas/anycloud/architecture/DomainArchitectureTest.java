package com.aipaas.anycloud.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.mapstruct.Mapper;
import org.springframework.web.bind.annotation.RestController;

/**
 * ArchUnit guard — docs/conventions/folder-structure.md 의 convention 을 컴파일 후 test 로 자동
 * enforce. 위반 시 build fail. 신규 commit 의 회귀 자동 차단.
 *
 * <p>각 rule 의 의도는 본 file 의 javadoc + folder-structure.md § 1-2 참조.
 *
 * <p>적용 범위는 의도적으로 좁음 — ArchUnit 의 capturing wildcard 가 same-vs-different 도메인 구별
 * 표현이 어려워, 신뢰성 높은 단순 rule 만 채택. cross-domain internal access 같은 복잡 rule 은 PR
 * review 시점에 manual 검증.
 */
@AnalyzeClasses(
        packages = "com.aipaas.anycloud",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class DomainArchitectureTest {

    /**
     * Controller 는 항상 {@code domain.{feature}.web} 패키지 안 — feature-first migration 의 핵심.
     *
     * <p>controller/ flat folder 회귀 차단.
     */
    @ArchTest
    static final ArchRule controllers_only_in_web_package =
            classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage("..web..");

    /**
     * MapStruct @Mapper 는 {@code mapper/} 패키지 안.
     *
     * <p>sub-feature 안의 mapper 도 sub-feature/mapper/ 로 분리.
     *
     * <p>적용 안 됨: 일반 utility static method mapper (@Mapper 미부착) — internal/ 거주 OK.
     */
    @ArchTest
    static final ArchRule mapstruct_mappers_in_mapper_package =
            classes().that().areAnnotatedWith(Mapper.class).should().resideInAPackage("..mapper..");

    /**
     * configuration/ 패키지의 class 가 도메인 internal/ 직접 의존 금지.
     *
     * <p>configuration/ 는 cross-cutting (auth, security, web 설정). 도메인 specific impl 알 필요 X.
     * 도메인 service 의 root interface 또는 properties 만 의존해야.
     */
    @ArchTest
    static final ArchRule configuration_should_not_use_domain_internal = noClasses()
            .that()
            .resideInAPackage("com.aipaas.anycloud.configuration..")
            .should()
            .accessClassesThat()
            .resideInAPackage("..domain..internal..")
            .allowEmptyShould(true);

    /**
     * common/ 패키지의 class 가 도메인 internal/ 직접 의존 금지.
     *
     * <p>common/ 는 cross-cutting helper (error, util, validation, web filter). 도메인 specific
     * impl 결합 시 cross-cutting 의미 깨짐.
     */
    @ArchTest
    static final ArchRule common_should_not_use_domain_internal = noClasses()
            .that()
            .resideInAPackage("com.aipaas.anycloud.common..")
            .should()
            .accessClassesThat()
            .resideInAPackage("..domain..internal..")
            .allowEmptyShould(true);

    /**
     * {@code api/request/} 안의 public class 는 *Request 또는 *Dto suffix.
     *
     * <p>{@code *Dto} 는 legacy exception — 신규는 *Request 권장. 단순 enum / interface / inner
     * record 는 예외 — controller {@code @RequestBody} 가 받는 top-level class 의 일관성만 보장.
     */
    @ArchTest
    static final ArchRule request_classes_named_consistently = classes()
            .that()
            .resideInAPackage("..api.request..")
            .and()
            .areTopLevelClasses()
            .and()
            .areNotEnums()
            .and()
            .areNotInterfaces()
            .should()
            .haveSimpleNameEndingWith("Request")
            .orShould()
            .haveSimpleNameEndingWith("Dto")
            .allowEmptyShould(true);

    /**
     * {@code api/response/} 안의 public class 는 *Response 또는 *Dto suffix.
     *
     * <p>{@code *Dto} 는 legacy exception. 신규는 *Response 권장.
     */
    @ArchTest
    static final ArchRule response_classes_named_consistently = classes()
            .that()
            .resideInAPackage("..api.response..")
            .and()
            .areTopLevelClasses()
            .and()
            .areNotEnums()
            .and()
            .areNotInterfaces()
            .should()
            .haveSimpleNameEndingWith("Response")
            .orShould()
            .haveSimpleNameEndingWith("Dto")
            .allowEmptyShould(true);
}

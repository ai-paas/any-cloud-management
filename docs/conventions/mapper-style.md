# Mapper 컨벤션 (Entity ↔ DTO ↔ Domain)

> 기준 문서 — DTO 변환 컨벤션의 상세.

## 배경

Mapper 패턴이 흩어져 신규 contributor 가 "어디에 mapper 를 두지?" 매번 질문하는 문제를 단일화하기
위한 컨벤션. 발생할 수 있는 변종 (정적 메서드 / service 내부 static / DTO 안 `@Builder` 매핑 /
interface default static) 대신 **MapStruct interface** 로 통일.

## 결론 — **신규 mapper 는 MapStruct interface**

```java
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ClusterMapper {

    @Mapping(target = "createdAt", source = "createdTimestamp")
    Cluster toDomain(ClusterEntity entity);

    ClusterEntity toEntity(Cluster domain);

    List<Cluster> toDomainList(List<ClusterEntity> entities);
}
```

### 왜 MapStruct ?

- **컴파일 타임 검증**: source / target field mismatch 가 컴파일 에러. 잘못된 field 이름은 runtime
  까지 살지 않는다.
- **성능**: reflection 사용 안 함 — 생성된 코드가 `new Target(...)` 직접 호출.
- **Lombok 호환**: `lombok-mapstruct-binding` 으로 annotation processor 순서 보장
  (build.gradle 에 이미 wired).
- **Spring 통합**: `componentModel = "spring"` → mapper 가 `@Component` 로 자동 등록 → 다른 bean
  에서 `@Autowired` (`@RequiredArgsConstructor` 권장).

### 빌드 setup

루트 `build.gradle` 의 `ext.mapstructVersion` + `ext.lombokMapstructBindingVersion` 가 단일
진실. `apps/anycloud/build.gradle` 의 dependencies 에 다음 3 줄이 wired:

```groovy
implementation "org.mapstruct:mapstruct:${rootProject.ext.mapstructVersion}"
annotationProcessor "org.mapstruct:mapstruct-processor:${rootProject.ext.mapstructVersion}"
annotationProcessor "org.projectlombok:lombok-mapstruct-binding:${rootProject.ext.lombokMapstructBindingVersion}"
```

`lombok-mapstruct-binding` 이 빠지면 Lombok 의 `@RequiredArgsConstructor` 가 만든 constructor 를
MapStruct 가 못 봐 NPE 발생. **반드시 함께 import**.

## 위치 규칙

- Entity → Domain / Domain → DTO mapper → `service/{domain}/{Domain}Mapper.java` (interface).
- Service 내부에서만 쓰이는 변환 → service impl 안의 private static method 허용 (소규모).
- Controller 의 DTO 조립 → controller 안의 private method 허용 (소규모).
- **DO NOT** : DTO 클래스 안에 변환 로직 (`@Builder + AllArgsConstructor` 가 매핑 책임 짊어지지 말 것).

## 현재 mapper 인벤토리

| Mapper | 패턴 | 비고 |
|---|---|---|
| HelmRepoMapper | MapStruct | |
| OperationMapper | MapStruct | 18 field |
| VmClusterStateHistoryMapper | MapStruct | 10 field |
| FleetUpgradeRunMapper | MapStruct | 16 field |
| ClusterMapper | MapStruct | status enum 변환 (`statusToName` / `statusFromName` / `normalizeGpuFlag` default method) |
| AddonMapper | MapStruct | addon entity ↔ DTO |
| ClusterSpecMapper | 헬퍼 (DTO→DTO) | request DTO 분해 — MapStruct 부적합 |
| HelmExceptionMapper | 헬퍼 (예외 분류) | exception classifier — MapStruct 부적합 |
| GpuFlavorMapper | 헬퍼 (도메인 로직) | GPU instance 자동 주입 — MapStruct 부적합 |

entity ↔ domain mapper 6 개 모두 MapStruct. 잔여 3 개는 mapper 가 아닌 헬퍼 (변환이 아닌 도메인
로직 / 예외 분류) — MapStruct 부적합으로 그대로 유지.

## 점진 마이그레이션 trigger

기존 정적 mapper 는 그대로 동작 — 한 번에 다 옮기지 말 것. 다음 시점에 1 개씩 전환:

1. 해당 mapper / DTO 가 어차피 수정될 때 (기능 추가, 필드 변경).
2. 신규 field 추가가 누락되어 bug 가 발생했을 때 (MapStruct 면 컴파일 에러).
3. mapper 가 5+ field 이상 다루기 시작할 때 (boilerplate 비용 > 학습 비용).

각 전환:
1. 새 interface `*Mapper` 작성 (`@Mapper(componentModel = "spring")`).
2. 기존 호출자가 static method → autowired bean instance 호출로 변경.
3. 기존 mapper 클래스 삭제.
4. 단위 테스트로 mapping 결과 동일 확인.

## ModelMapper 대체

`apps/anycloud/build.gradle` 에는 ModelMapper 3.1.1 가 여전히 wired — 기존 사용처가 있어 즉시
제거 불가. 점진 마이그레이션 완료 후 `implementation 'org.modelmapper:modelmapper:3.1.1'` 줄 제거.

## Anti-pattern (작성하지 말 것)

```java
// ❌ DTO 안에 매핑 — DTO 가 entity 를 import 함. 의존 방향 역행.
public record ClusterResponse(String id, ...) {
    public static ClusterResponse from(ClusterEntity e) { ... }
}

// ❌ ModelMapper 신규 사용 — runtime reflection. MapStruct 로 대체.
ModelMapper modelMapper = new ModelMapper();
return modelMapper.map(entity, ClusterDto.class);

// ❌ Service 내 11+ field 정적 toDto — MapStruct 가 더 짧고 안전.
public static ClusterDto toDto(ClusterEntity e) {
    return ClusterDto.builder().id(e.getId()).name(e.getName()).... .build();
}
```

## 예시 — 기존 ClusterMapper 전환

기존 (static):

```java
public final class ClusterMapper {
    public static Cluster toDomain(ClusterEntity e) {
        return new Cluster(e.getId(), e.getName(), ...);
    }
}
```

MapStruct 후:

```java
@Mapper(componentModel = "spring")
public interface ClusterMapper {
    Cluster toDomain(ClusterEntity e);
    ClusterEntity toEntity(Cluster domain);
}

// 호출자:
@RequiredArgsConstructor
class ClusterServiceImpl {
    private final ClusterMapper clusterMapper;   // Spring 자동 주입
    ...
    return clusterMapper.toDomain(entity);
}
```

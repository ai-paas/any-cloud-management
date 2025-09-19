# Any Cloud Management DevContainer

이 DevContainer는 Any Cloud Management 프로젝트를 위한 완전한 개발 환경을 제공합니다.

## 포함된 구성 요소

### 개발 환경
- **OpenJDK 21** - 최신 Java 런타임
- **Gradle 8.10.2** - 빌드 도구
- **Spring Boot 3.2.5** - 웹 애플리케이션 프레임워크
- **VS Code Java Extension Pack** - Java 개발을 위한 확장

### 데이터베이스
- **MariaDB 10.11.3** - 개발용 데이터베이스
- 자동으로 `anycloud` 데이터베이스와 사용자 생성

### 추가 도구
- **Docker-in-Docker** - 컨테이너 개발 지원
- **kubectl & Helm** - Kubernetes 관리
- **Git & GitHub CLI** - 버전 관리

## 사용 방법

1. **DevContainer 시작**
   - VS Code에서 "Reopen in Container" 선택
   - 또는 Command Palette에서 "Dev Containers: Reopen in Container"

2. **개발 서버 시작**
   ```bash
   ./start-dev.sh      # 개발 모드
   ./start-debug.sh    # 디버그 모드 (포트 8080)
   ```

3. **애플리케이션 접근**
   - 웹 애플리케이션: http://localhost:8888
   - 데이터베이스: localhost:3306
   - 디버그 포트: 8080

## 프로젝트 구조

```
any-cloud-management/
├── .devcontainer/          # DevContainer 설정
│   ├── devcontainer.json   # VS Code DevContainer 설정
│   ├── docker-compose.yml  # 서비스 구성
│   ├── Dockerfile         # 개발 환경 이미지
│   └── postCreateCommand.sh # 초기화 스크립트
├── anycloud/              # 메인 애플리케이션
│   └── src/main/java/     # Java 소스 코드
└── build.gradle           # Gradle 빌드 설정
```

## 환경 변수

- `DATABASE_HOST`: anycloud-db
- `DATABASE_PORT`: 3306
- `DATABASE_NAME`: anycloud
- `DATABASE_USERID`: anycloud
- `DATABASE_USERPASS`: anycloud
- `THANOS_URL`: https://localhost:9000

## 개발 팁

1. **Hot Reload**: Spring Boot DevTools가 활성화되어 있어 코드 변경 시 자동 재시작
2. **디버깅**: VS Code의 디버그 기능을 사용하여 브레이크포인트 설정 가능
3. **데이터베이스**: MariaDB는 컨테이너 재시작 시에도 데이터가 유지됩니다
4. **포트 포워딩**: 8888, 3306, 8080 포트가 자동으로 포워딩됩니다

## 문제 해결

- **빌드 오류**: `./gradlew clean build` 실행
- **데이터베이스 연결 오류**: MariaDB 컨테이너가 완전히 시작될 때까지 대기
- **포트 충돌**: 다른 애플리케이션에서 사용 중인 포트 확인

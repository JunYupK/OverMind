# 이 이미지는 빌드하지 않는다. CI가 만든 jar를 담기만 한다 (D-H).
#
# 이유 셋:
#  1. 이 프로젝트에는 의존성 잠금이 없다 (gradle.lockfile도 verification-metadata도
#     dependencyLocking도 없다). 박스에서 재빌드하면 CI가 verify로 검증한 바이트와
#     다른 전이 의존성이 섞일 수 있다.
#  2. 박스가 앱과 PG를 같이 돌리기에도 빠듯하다. 거기에 Gradle 데몬을 얹지 않는다.
#  3. Maven Central이 HTTP 429를 낸 적이 두 번 있다. 업스트림 레이트 리밋으로
#     배포가 실패할 수 있는 경로를 만들지 않는다.
#
# RUN이 하나도 없다. 실행되는 명령이 없어서 buildx가 QEMU 없이 멀티아치를 만든다.
FROM eclipse-temurin:21-jre

# useradd 대신 숫자 UID. /etc/passwd 항목이 없어도 JVM은 동작하고,
# RUN이 없어야 크로스 아키텍처 빌드에 에뮬레이션이 필요 없다.
COPY --chown=10001:10001 build/libs/overmind-*.jar /app/app.jar
USER 10001:10001

EXPOSE 8080

# MaxRAMPercentage는 compose의 mem_limit과 짝이다. 한도가 없으면 JVM이 호스트
# 메모리 기준으로 힙을 잡고, 메모리 압박 때 OOM killer가 앱보다 PG를 먼저 죽인다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]

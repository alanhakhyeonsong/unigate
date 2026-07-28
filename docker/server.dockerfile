FROM eclipse-temurin:21-jre-jammy

ARG MODULE_NAME

ENV WORKDIR=/app
ENV TMPDIR=/tmp
ENV TZ=Asia/Seoul
# 프로필 기본값을 두지 않는다(fail-closed). entrypoint 가 미지정 시 기동을 실패시킨다.
# local 프로필에는 개발용 자격증명과 디버그 엔드포인트가 포함되어 있다.

WORKDIR ${WORKDIR}

COPY ${MODULE_NAME}/build/libs/app.jar ./app.jar
COPY docker/docker-entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh \
    && mkdir -p ${WORKDIR}${TMPDIR}

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]

# 빌드 (MODULE_NAME 은 저장소 루트 기준 **상대 경로**다):
#   docker build --build-arg MODULE_NAME=gateway            -f docker/server.dockerfile .
#   docker build --build-arg MODULE_NAME=iam                -f docker/server.dockerfile .
#   docker build --build-arg MODULE_NAME=samples/downstream-demo -f docker/server.dockerfile .
#
# 세 모듈 모두 bootJar 잔 이름이 `app.jar` 로 통일돼 있다. 이름이 갈리면 `.dockerignore`
# 화이트리스트(`!**/build/libs/app.jar`)에 걸려 **컨텍스트에서 아예 제외**되므로,
# Dockerfile 만 고쳐서는 해결되지 않는다.
#
# 샘플 FE 는 JVM 앱이 아니라 별도다: samples/frontend-demo/Dockerfile (nginx).
#
# 실행 시 런타임 프로필 주입: docker run -e SPRING_PROFILES_ACTIVE={profile} {image_name}

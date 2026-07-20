FROM eclipse-temurin:21-jre-jammy

ARG MODULE_NAME

ENV WORKDIR=/app
ENV TMPDIR=/tmp
ENV TZ=Asia/Seoul
ENV SPRING_PROFILES_ACTIVE=local

WORKDIR ${WORKDIR}

COPY ${MODULE_NAME}/build/libs/app.jar ./app.jar
COPY docker/docker-entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh \
    && mkdir -p ${WORKDIR}${TMPDIR}

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]

# docker build -t {image_name} --build-arg MODULE_NAME=gateway -f docker/server.dockerfile .
# 실행 시 런타임 프로필 주입: docker run -e SPRING_PROFILES_ACTIVE={profile} {image_name}

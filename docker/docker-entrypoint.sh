#!/bin/sh
exec java ${JAVA_OPTS:-} \
  -server \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-local} \
  -Dspring.jmx.enabled=false \
  -Duser.timezone=${TZ:-UTC} \
  -Duser.language=ko \
  -Duser.country=KR \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Dsun.net.inetaddr.ttl=0 \
  -Dsun.net.inetaddr.negative.ttl=30 \
  -Djava.io.tmpdir=/app/tmp \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:ParallelGCThreads=4 \
  -XX:ConcGCThreads=1 \
  -jar ${WORKDIR}/app.jar

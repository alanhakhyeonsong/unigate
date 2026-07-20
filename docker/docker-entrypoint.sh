#!/bin/sh
set -e

# 프로필 기본값을 두지 않는다(fail-closed).
# local 프로필에는 개발용 자격증명과 디버그 엔드포인트가 들어 있어,
# 배포 시 오버레이를 빠뜨리면 그것들이 그대로 운영에 뜬다.
# 기본값이 있으면 그 사고가 "조용히 성공"하므로, 미지정 시 기동을 실패시킨다.
if [ -z "${SPRING_PROFILES_ACTIVE:-}" ]; then
  echo "FATAL: SPRING_PROFILES_ACTIVE 가 지정되지 않았습니다." >&2
  echo "       배포 시 values-<env>.yaml 로 명시하세요 (예: alpha)." >&2
  exit 1
fi

exec java ${JAVA_OPTS:-} \
  -server \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
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

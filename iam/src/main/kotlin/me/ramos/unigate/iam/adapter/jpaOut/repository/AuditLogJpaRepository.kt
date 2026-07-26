package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.AuditLogEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 감사 기록 저장소.
 *
 * 조회 메서드를 두지 않는다 — 지금 애플리케이션에는 감사를 **읽는** 유스케이스가 없다.
 * (조회는 운영자가 SQL 로 한다. 감사 조회 API 가 필요해지면 그 자체가 인가 설계를 요구하는
 * 별도 주제다 — 누가 누구의 감사를 볼 수 있는가.)
 *
 * 테스트는 `JpaRepository` 가 기본 제공하는 `findAll()` 로 검증한다.
 */
interface AuditLogJpaRepository : JpaRepository<AuditLogEntity, Long>

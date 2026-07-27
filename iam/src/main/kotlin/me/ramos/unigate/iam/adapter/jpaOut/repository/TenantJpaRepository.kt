package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.TenantEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TenantJpaRepository : JpaRepository<TenantEntity, String>

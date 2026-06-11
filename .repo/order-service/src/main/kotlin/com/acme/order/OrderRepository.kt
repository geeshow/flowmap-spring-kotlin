package com.acme.order

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<OrderEntity, Long>

@Entity
@Table(name = "orders")
class OrderEntity(
    @Id val id: Long,
    val userId: Long,
    val amount: Long,
    val status: String,
)

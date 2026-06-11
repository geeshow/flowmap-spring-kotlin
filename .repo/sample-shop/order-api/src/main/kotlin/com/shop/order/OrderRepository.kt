package com.shop.order

import org.springframework.data.jpa.repository.JpaRepository

// No @Repository annotation: still detected as REPOSITORY via JpaRepository base type.
interface OrderRepository : JpaRepository<Order, Long> {
    fun findByUserId(userId: Long): List<Order>
}

data class Order(val id: Long, val status: String, val amount: Long)

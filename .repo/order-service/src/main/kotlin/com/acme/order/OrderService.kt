package com.acme.order

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val userClient: UserClient,                 // S2S HTTP -> user-service
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val redisTemplate: StringRedisTemplate,
) {
    fun placeOrder(userId: Long, amount: Long): OrderEntity {
        // S2S HTTP call into another analyzed service (user-service)
        val user = userClient.getUser(userId)

        val saved = orderRepository.save(OrderEntity(0, userId, amount, "NEW"))

        // cache (Redis)
        redisTemplate.opsForValue().set("order:${saved.id}", saved.status)

        // publish domain event (Kafka) -> consumed by notification-service
        kafkaTemplate.send("order.created", OrderCreated(saved.id, userId, amount))
        return saved
    }
}

data class OrderCreated(val orderId: Long, val userId: Long, val amount: Long)

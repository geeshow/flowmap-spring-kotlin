package com.acme.notification

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderEventListener(
    private val notificationService: NotificationService,
) {
    // Consumes the "order.created" topic that order-service produces (event-driven S2S).
    @KafkaListener(topics = ["order.created"], groupId = "notification")
    fun onOrderCreated(event: Map<String, Any>) {
        val userId = (event["userId"] as Number).toLong()
        notificationService.notifyUser(userId)
    }
}

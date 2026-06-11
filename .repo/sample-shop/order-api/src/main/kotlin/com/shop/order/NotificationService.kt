package com.shop.order

import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class NotificationService(
    private val webClient: WebClient,
) {
    fun notify(orderId: Long) {
        // external HTTP call via WebClient
        webClient.post()
    }
}

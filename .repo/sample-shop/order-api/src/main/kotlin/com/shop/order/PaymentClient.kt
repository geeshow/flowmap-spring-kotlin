package com.shop.order

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping

// Feign client: every method call on it is treated as an EXTERNAL edge.
@FeignClient(name = "payment", url = "https://pay.internal")
interface PaymentClient {
    @PostMapping("/charge")
    fun charge(orderId: Long, amount: Long): String
}

package com.acme.order

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping
    fun create(req: CreateOrderRequest): OrderResponse {
        val order = orderService.placeOrder(req.userId, req.amount)
        return OrderResponse(order.id, order.status)
    }
}

data class CreateOrderRequest(val userId: Long, val amount: Long)
data class OrderResponse(val id: Long, val status: String)

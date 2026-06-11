package com.shop.order

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping("/orders")
    fun placeOrder(@RequestBody req: OrderRequest): OrderResponse {
        // synchronous controller -> service call
        val order = orderService.placeOrder(req)
        return OrderResponse(order.id, order.status)
    }

    @PostMapping("/orders/{id}/notify")
    fun notifyOrder(id: Long): String {
        // fire-and-forget asynchronous notification
        orderService.sendConfirmationAsync(id)
        return "accepted"
    }
}

data class OrderRequest(val userId: Long, val amount: Long)
data class OrderResponse(val id: Long, val status: String)

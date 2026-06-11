package com.shop.order

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val paymentClient: PaymentClient,
    private val notificationService: NotificationService,
) {
    fun placeOrder(req: OrderRequest): Order {
        val saved = orderRepository.save(Order(0, "NEW", req.amount))
        // synchronous external HTTP call via Feign client
        paymentClient.charge(saved.id, req.amount)
        return saved
    }

    @Async
    fun sendConfirmationAsync(orderId: Long) {
        // @Async -> the call into this method is asynchronous
        val order = orderRepository.findById(orderId)
        notificationService.notify(orderId)
    }

    suspend fun reconcile(orderId: Long): Boolean {
        // suspend fun: calls into it are coroutine/async calls
        val order = orderRepository.findById(orderId)
        GlobalScope.launch {
            // call inside a coroutine builder -> async context
            notificationService.notify(orderId)
        }
        return order != null
    }
}

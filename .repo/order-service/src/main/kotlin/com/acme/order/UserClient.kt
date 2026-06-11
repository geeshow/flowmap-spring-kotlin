package com.acme.order

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

// Outbound HTTP to the "user-service" project. When user-service has been analyzed,
// this resolves to its UserController.getUser endpoint (S2S), not a generic external.
@FeignClient(name = "user-service", url = "\${service-url.user}")
interface UserClient {
    @GetMapping("/internal/users/{id}")
    fun getUser(@PathVariable id: Long): UserView
}

data class UserView(val id: Long, val name: String)

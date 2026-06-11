package com.acme.notification

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "user-service", url = "\${service-url.user}")
interface UserClient {
    @GetMapping("/internal/users/{id}")
    fun getUser(@PathVariable id: Long): UserView
}

data class UserView(val id: Long, val name: String)

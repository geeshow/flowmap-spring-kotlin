package com.acme.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/users")
class UserController(
    private val userService: UserService,
) {
    // This is the endpoint order-service's UserClient.getUser() calls (S2S target).
    @GetMapping("/{id}")
    fun getUser(@PathVariable id: Long): UserView {
        val u = userService.find(id)
        return UserView(u.id, u.name)
    }
}

data class UserView(val id: Long, val name: String)

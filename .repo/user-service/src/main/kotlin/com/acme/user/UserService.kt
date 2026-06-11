package com.acme.user

import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
) {
    fun find(id: Long): UserEntity =
        userRepository.findById(id) ?: throw NoSuchElementException("user $id")
}

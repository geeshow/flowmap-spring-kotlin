package com.acme.notification

import org.springframework.stereotype.Service

@Service
class NotificationService(
    private val userClient: UserClient,                 // S2S HTTP -> user-service
    private val emailSender: EmailSender,
) {
    fun notifyUser(userId: Long) {
        val user = userClient.getUser(userId)            // resolves to user-service endpoint
        emailSender.send(user.name)
    }
}

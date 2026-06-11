package com.acme.notification

import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class EmailSender(
    private val restTemplate: RestTemplate,
) {
    companion object {
        const val EMAIL_API = "https://api.email-vendor.com/v1/send"
    }

    fun send(to: String) {
        // pure external (3rd-party), not an analyzed service
        restTemplate.postForObject(EMAIL_API, to, String::class.java)
    }
}

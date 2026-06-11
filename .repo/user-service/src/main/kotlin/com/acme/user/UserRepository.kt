package com.acme.user

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<UserEntity, Long>

@Entity
@Table(name = "users")
class UserEntity(
    @Id val id: Long,
    val name: String,
)

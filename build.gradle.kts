plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    // JDBC driver only needed when syncing to Postgres
    runtimeOnly("org.postgresql:postgresql:42.7.3")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.callgraph.MainKt")
}

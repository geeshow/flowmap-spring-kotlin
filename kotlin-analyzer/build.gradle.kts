plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // K1 frontend: PSI + BindingContext + ConstantExpressionEvaluator.
    // Fully on Maven Central — no JetBrains -for-ide artifacts required.
    // NOTE: kotlin-compiler-embeddable SHADES IntelliJ classes to the
    // `org.jetbrains.kotlin.com.intellij.*` package; the resolver code imports
    // PsiElement/Disposer from there (not plain `com.intellij.*`).
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.flowmap.callgraph.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":adapters:tourism-api"))
    implementation(project(":modules:audio"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:community"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:insights"))
    implementation(project(":modules:journey"))
    implementation(project(":modules:operations"))
    implementation(project(":modules:shared-web"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation(libs.flyway.core)
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.testcontainers)
    testImplementation(project(":adapters:persistence-jdbc"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

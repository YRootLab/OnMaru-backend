plugins {
    id("onmaru.spring-boot-app-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    dependencies {
        dependency("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
    }
}

dependencies {
    implementation(project(":adapters:persistence-jdbc"))
    implementation(project(":adapters:tourism-api"))
    implementation(project(":adapters:persistence-jdbc"))
    implementation(project(":modules:audio"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:community"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:insights"))
    implementation(project(":modules:journey"))
    implementation(project(":modules:operations"))
    implementation(project(":modules:shared-web"))
    implementation(project(":modules:stamp"))

    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
    }

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.testcontainers)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.postgresql)
}

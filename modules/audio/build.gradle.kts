plugins {
    id("onmaru.java-library-conventions")
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":modules:catalog"))

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

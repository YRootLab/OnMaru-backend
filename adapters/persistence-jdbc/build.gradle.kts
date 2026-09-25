plugins {
    id("onmaru.java-library-conventions")
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation(project(":modules:journey"))
    implementation(project(":modules:operations"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:community"))
    implementation(project(":modules:insights"))
    implementation(project(":modules:shared-web"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

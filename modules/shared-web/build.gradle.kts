plugins {
    id("onmaru.java-library-conventions")
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
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
    }

    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

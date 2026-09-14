pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "onmaru-backend"

include("apps:spring-api")
include("adapters:tourism-api")
include("modules:catalog")
include("modules:operations")
include("modules:shared-web")

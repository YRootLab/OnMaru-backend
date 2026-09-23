val profileEnabled = providers.gradleProperty("onmaru.ci.performance.enabled")
    .map(String::toBoolean)
    .getOrElse(false)

val requestedWorkers = providers.gradleProperty("onmaru.ci.performance.max-workers")
    .map(String::toInt)
    .getOrElse(4)
val effectiveWorkers = requestedWorkers.coerceIn(1, 4)

val cacheEnabled = providers.gradleProperty("onmaru.ci.performance.build-cache")
    .map(String::toBoolean)
    .getOrElse(true)

if (profileEnabled) {
    gradle.startParameter.maxWorkerCount = effectiveWorkers
    gradle.startParameter.isBuildCacheEnabled = cacheEnabled
}

gradle.rootProject {
    tasks.register("ciPerformanceProfile") {
        group = "verification"
        description = "Records the effective opt-in CI Gradle worker and cache profile."

        doLast {
            val report = layout.buildDirectory.file("ci-performance/gradle-profile.json").get().asFile
            report.parentFile.mkdirs()
            report.writeText(
                """
                {
                  "profileEnabled": $profileEnabled,
                  "requestedWorkers": $requestedWorkers,
                  "effectiveWorkers": ${gradle.startParameter.maxWorkerCount},
                  "buildCacheEnabled": ${gradle.startParameter.isBuildCacheEnabled}
                }
                """.trimIndent() + "\n",
            )
            logger.lifecycle("CI Gradle profile: workers=${gradle.startParameter.maxWorkerCount}, build-cache=${gradle.startParameter.isBuildCacheEnabled}")
        }
    }
}

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.testing.Test

plugins {
    base
}

allprojects {
    group = "io.graphcrud"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    apply(plugin = "java-library")

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
    }

    dependencyLocking {
        lockFile = rootProject.file("gradle/dependency-locks/${project.name}.lockfile")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.register("resolveDependencies") {
        group = "verification"
        description = "Resolves this module's configurations under dependency verification."

        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

val allowedProjectDependencies = mapOf(
    ":model" to emptySet<String>(),
    ":application" to setOf(":model"),
    ":infrastructure" to setOf(":application"),
    ":launcher" to setOf(":application", ":infrastructure"),
)

fun findModuleBoundaryViolations(actual: Map<String, Set<String>>): Map<String, Set<String>> =
    actual.mapValues { (projectPath, dependencies) ->
        dependencies - allowedProjectDependencies.getValue(projectPath)
    }.filterValues { it.isNotEmpty() }

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies the public Stage 0 module graph."

    doLast {
        allowedProjectDependencies.forEach { (projectPath, _) ->
            val module = project(projectPath)
            check(module.projectDir.resolve("build.gradle.kts").isFile) {
                "$projectPath must expose its Gradle module interface through build.gradle.kts"
            }

            val actualDependencies = module.configurations
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { ":${it.name}" }
                .toSet()

            val violations = findModuleBoundaryViolations(mapOf(projectPath to actualDependencies))
            check(violations.isEmpty()) {
                "$projectPath leaks across the module seam: ${violations.getValue(projectPath)} is not allowed"
            }
        }
    }
}

val resolveAllDependencies by tasks.registering {
    group = "verification"
    description = "Resolves every resolvable configuration under dependency verification."
    dependsOn(subprojects.map { it.tasks.named("resolveDependencies") })
}

val verifyDependencyLockLocations by tasks.registering {
    group = "verification"
    description = "Verifies that module lock state is centralized under gradle/dependency-locks."

    doLast {
        subprojects.forEach { module ->
            check(rootProject.file("gradle/dependency-locks/${module.name}.lockfile").isFile) {
                "${module.path} must keep its lock state under gradle/dependency-locks"
            }
            check(!module.file("gradle.lockfile").exists()) {
                "${module.path} must not keep a lock file in its module directory"
            }
        }
    }
}

val testModuleBoundaryRules by tasks.registering {
    group = "verification"
    description = "Proves that infrastructure leakage is rejected."

    doLast {
        val forbiddenFixture = mapOf(":model" to setOf(":infrastructure"))
        check(findModuleBoundaryViolations(forbiddenFixture).isNotEmpty())
    }
}

val ciFast by tasks.registering {
    group = "verification"
    description = "Runs the same deterministic, Docker-free checks locally and in CI."
    dependsOn(
        tasks.named("check"),
        subprojects.map { it.tasks.named("check") },
        verifyModuleBoundaries,
        testModuleBoundaryRules,
        resolveAllDependencies,
        verifyDependencyLockLocations,
    )
}

dependencies {
    implementation(project(":application"))
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.46.0")
    implementation("com.github.jsqlparser:jsqlparser:5.3")
    implementation("org.neo4j.driver:neo4j-java-driver:6.2.0")
    testImplementation("org.neo4j.test:neo4j-harness:2026.06.0")
}

tasks.test {
    useJUnitPlatform { excludeTags("neo4j") }
}

tasks.register<Test>("neo4jIntegrationTest") {
    group = "verification"
    description = "Runs the Neo4j-backed GraphStore contract."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("neo4j") }
    shouldRunAfter(tasks.test)
}

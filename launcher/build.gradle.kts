plugins {
    application
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation("org.neo4j.driver:neo4j-java-driver:6.2.0")
}

application {
    mainClass.set("io.graphcrud.launcher.GraphCrudMain")
}

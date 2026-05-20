plugins {
    java
    application
}

application {
    mainClass.set("com.risksentinel.mcp.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

group = "com.risksentinel"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("tools.jackson.core:jackson-databind:3.1.3")
    implementation("org.slf4j:slf4j-api:2.0.18")

    // MCP Java SDK (stdio transport)
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.2")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

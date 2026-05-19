plugins {
    java
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
    // Latency measurement (Phase 5)
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    // Metrics (Phase 5)
    implementation("io.micrometer:micrometer-core:1.16.5")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")

    // Structured logging (Phase 5)
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // Audit persistence (Phase 6)
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    testImplementation("net.jqwik:jqwik:1.9.3")

    // AssertJ for fluent assertions
    testImplementation("org.assertj:assertj-core:3.27.3")

    // Concurrency testing
    testImplementation("org.openjdk.jcstress:jcstress-core:0.16")
    testAnnotationProcessor("org.openjdk.jcstress:jcstress-core:0.16")

    // Runtime launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

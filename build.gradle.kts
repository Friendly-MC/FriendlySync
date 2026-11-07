plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.lyy"
version = "1.0.0-SNAPSHOT"
description = "Production-grade cross-server player synchronization plugin for Paper and Folia"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    // Bundled and relocated to avoid conflicts with other plugins.
    implementation("org.bstats:bstats-bukkit:3.2.1")

    // Paper / Folia API (compileOnly)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Database & Connection Pooling (resolved dynamically via bootstrap PluginLoader / libraries)
    compileOnly("com.zaxxer:HikariCP:7.1.0")
    compileOnly("com.mysql:mysql-connector-j:9.7.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    // Redis (resolved dynamically via bootstrap PluginLoader / libraries)
    compileOnly("redis.clients:jedis:8.0.2")

    // Compression (resolved dynamically via bootstrap PluginLoader / libraries)
    compileOnly("com.github.luben:zstd-jni:1.5.7-23")

    // Logging API (slf4j is provided by Paper/Folia runtime)
    compileOnly("org.slf4j:slf4j-api:2.0.20")

    // Annotations
    compileOnly("org.jetbrains:annotations:26.1.0")

    // Test dependencies
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.h2database:h2:2.5.252")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("com.mysql:mysql-connector-j:9.7.0")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    testImplementation("redis.clients:jedis:8.0.2")
    testImplementation("com.github.luben:zstd-jni:1.5.7-23")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("org.bstats", "dev.lyy.friendlysync.libs.bstats")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

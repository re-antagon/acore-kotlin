plugins {
    kotlin("jvm") version "2.3.0"
}

group = "org.antagon"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    implementation("com.github.ajneb97:ConditionalEvents:4.65.1")    // Conditional Events api
    compileOnly("io.lumine:Mythic-Dist:5.9.5")                       // Mythic Mobs api
    compileOnly("net.luckperms:api:5.4")                             // LuckPerms api
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "org.antagon"
version = "1.3"

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
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://maven.playpro.com/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")   // PacketEvents API
    compileOnly("net.coreprotect:coreprotect:22.4")                  // CoreProtect API
    compileOnly("com.github.ajneb97:ConditionalEvents:4.65.1")       // Conditional Events api
    compileOnly("io.lumine:Mythic-Dist:5.9.5")                       // Mythic Mobs api
    compileOnly("net.luckperms:api:5.4")                             // LuckPerms api
    compileOnly("net.kyori:adventure-api:4.26.1")                    // Adventure API
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")       // Adventure Text MiniMessage
    compileOnly("me.clip:placeholderapi:2.11.6")                    // PlaceholderAPI
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")                   // SQLite JDBC API
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {                // Vault api
        exclude(group = "org.bukkit", module = "bukkit")
        exclude(group = "org.bukkit", module = "craftbukkit")
    }
    implementation(kotlin("stdlib"))
    implementation("org.reflections:reflections:0.10.2")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

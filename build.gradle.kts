plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "org.antagon"
version = "1.6"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
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
    exclusiveContent {
        forRepository { maven("https://repo.nightexpressdev.com/releases") }
        filter {
            includeGroup("su.nightexpress.excellenteconomy")
            includeGroup("su.nightexpress.nightcore")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.242-stable")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")   // PacketEvents API
    compileOnly("net.coreprotect:coreprotect:22.4")                  // CoreProtect API
    compileOnly("com.github.ajneb97:ConditionalEvents:4.65.1")       // Conditional Events api
    compileOnly("io.lumine:Mythic-Dist:5.9.5")                       // Mythic Mobs api
    compileOnly("net.luckperms:api:5.4")                             // LuckPerms api
    compileOnly("me.clip:placeholderapi:2.11.6")                    // PlaceholderAPI
    compileOnly("su.nightexpress.excellenteconomy:ExcellentEconomy:2.8.0") // ExcellentEconomy api
    compileOnly("su.nightexpress.nightcore:main:2.16.7")             // Nightcore API
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")                   // SQLite JDBC API
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {                // Vault api
        exclude(group = "org.bukkit", module = "bukkit")
        exclude(group = "org.bukkit", module = "craftbukkit")
    }
    implementation(kotlin("stdlib"))
    implementation("org.reflections:reflections:0.10.2")
    testImplementation(kotlin("test-junit"))
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

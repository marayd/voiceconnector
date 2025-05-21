plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
}

group = "org.mryd"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.plasmoverse.com/releases")
    maven("https://repo.plasmoverse.com/snapshots")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")

    implementation("de.maxhenkel.voicechat:voicechat-api:2.1.12")
    compileOnly("su.plo.voice.server:paper:2.1.4")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
}

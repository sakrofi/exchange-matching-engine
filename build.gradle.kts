plugins {
    id("java")
    id("me.champeau.jmh") version "0.7.2"
}

group = "trading"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("org.agrona:agrona:2.5.0")
}


tasks.register<JavaExec>("preprocessItch") {
    group = "replay"
    mainClass.set("trading.replay.itch.ItchToBinary")
    classpath = sourceSets["main"].runtimeClasspath

    args = listOfNotNull(
        project.findProperty("input")?.toString(),
        project.findProperty("output")?.toString()
    )
}

tasks.register<JavaExec>("replay") {
    description = "Runs end-to-end replay"
    group = "replay"
    mainClass.set("trading.replay.ReplayMain")
    classpath = sourceSets["main"].runtimeClasspath

    args = listOfNotNull(
        project.findProperty("input")?.toString(),
        project.findProperty("book")?.toString(),
        project.findProperty("sink")?.toString()
    )
}


tasks.test {
    useJUnitPlatform()
}
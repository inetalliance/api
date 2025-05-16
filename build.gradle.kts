description = "CG2 Sonar"
plugins {
    war
    id("com.github.johnrengelman.shadow") version "8.1.1"
}
dependencies {
    api(project(":api:angular"))
    api(project(":msg:aj"))
    implementation(project(":api:obj"))
    implementation(libs.bundles.mail)
    implementation(libs.hikariCP)
    implementation(libs.slack)
    implementation(libs.asterisk)
    implementation(libs.jooq)
    implementation(libs.servlet)
    implementation(libs.websocket)
    runtimeOnly(libs.postgresql)
}

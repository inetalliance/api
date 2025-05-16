description = "CG2 Sonar"
plugins {
    war
    id("com.github.johnrengelman.shadow") version "8.1.1"
}
dependencies {
    implementation(project(":sonar:angular"))
    implementation(project(":msg:aj"))
    implementation(project(":sonar:obj"))
    implementation(libs.bundles.mail)
    implementation(libs.hikariCP)
    implementation(libs.slack)
    implementation(libs.asterisk)
    implementation(libs.jooq)
    implementation(libs.servlet)
    implementation(libs.websocket)
    runtimeOnly(libs.postgresql)
}

description = "CG2 Sonar"
plugins {
  war
  id("com.bmuschko.cargo") version "2.5"
}
dependencies {
  implementation(project(":obj"))
  implementation("net.inetalliance.angular:base:1.1-SNAPSHOT")
  implementation("net.inetalliance:util:1.1-SNAPSHOT")
  implementation("net.inetalliance.msg:aj:1.1-SNAPSHOT")
  implementation("org.jooq:jooq:3.11.9")
  implementation("com.google.apis:google-api-services-analyticsreporting:v4-rev148-1.25.0")
  implementation("javax.servlet:javax.servlet-api:3.1.0")
  implementation("javax.websocket:javax.websocket-api:1.1")
  runtimeOnly("org.slf4j:slf4j-jdk14:1.7.30")
  runtimeOnly("org.postgresql:postgresql:42.2.5")
}
apply(from = "cargo.gradle")
tasks {
  register("deploy") {
    dependsOn("cargoDeployRemote")
  }
}

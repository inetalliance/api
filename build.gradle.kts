description = "CG2 Sonar"
plugins {
  war
  id("com.bmuschko.cargo") version "2.5"
}
dependencies {
  compile(project(":obj"))
  compile("net.inetalliance.angular:base:1.1-SNAPSHOT")
  compile("net.inetalliance:util:1.1-SNAPSHOT")
  compile("net.inetalliance.msg:aj:1.1-SNAPSHOT")
  compile("org.jooq:jooq:3.11.9")
  compile("com.google.apis:google-api-services-analyticsreporting:v4-rev148-1.25.0")
  compile("javax.servlet:javax.servlet-api:3.1.0")
  compile("javax.websocket:javax.websocket-api:1.1")
  runtimeOnly("org.slf4j:slf4j-jdk14:1.7.30")
  runtimeOnly("org.postgresql:postgresql:42.2.5")
}
apply(from = "cargo.gradle")
tasks {
  register("deploy") {
    dependsOn("cargoDeployRemote")
  }
}

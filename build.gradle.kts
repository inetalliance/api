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
  compileOnly("javax.servlet:javax.servlet-api:3.1.0")
  compileOnly("javax.websocket:javax.websocket-api:1.1")
  runtime("org.slf4j:slf4j-simple:1.8.0-beta4")
  runtime("org.postgresql:postgresql:42.2.5")
}
apply(from = "cargo.gradle")
tasks {
  register("deploy") {
    dependsOn("cargoDeployRemote")
  }
}

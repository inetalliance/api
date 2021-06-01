description = "CG2 Sonar"
plugins {
  war
  id("com.bmuschko.cargo") version "2.5"
}
dependencies {
  implementation(project(":obj"))
	implementation("joda-time:joda-time:2.2")
	implementation("javax.mail:mail:1.5.0-b01")
	implementation("com.zaxxer:HikariCP:2.7.8")
	implementation("com.slack.api:slack-api-client:1.0.0-RC3")
  implementation("net.inetalliance.angular:base:1.1-SNAPSHOT")
  implementation("net.inetalliance:util:1.1-SNAPSHOT")
	implementation("org.asteriskjava:asterisk-java:1.0.0-final")
  implementation("net.inetalliance:cli:1.1-SNAPSHOT")
  implementation("net.inetalliance:sql:1.1-SNAPSHOT")
  implementation("net.inetalliance:types:1.1-SNAPSHOT")
  implementation("net.inetalliance:validation:1.1-SNAPSHOT")
  implementation("net.inetalliance:log:1.1-SNAPSHOT")
  implementation("net.inetalliance:funky:1.1-SNAPSHOT")
  implementation("net.inetalliance:daemonic:1.1-SNAPSHOT")
  implementation("net.inetalliance:potion:6.1-SNAPSHOT")
  implementation("net.inetalliance.msg:aj:1.1-SNAPSHOT")
	implementation("net.inetalliance.msg:cg:2.1-SNAPSHOT") 
	implementation("net.inetalliance.msg:bjx:6.1-SNAPSHOT") 
	implementation("net.inetalliance.msg:aj:1.1-SNAPSHOT")
	implementation("com.google.api-client:google-api-client:1.20.0")
	implementation("com.google.api-client:google-api-client-gson:1.20.0")
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

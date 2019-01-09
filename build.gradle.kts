description = "CG2 Sonar"
apply(plugin="war")
dependencies {
  compile(project(":angular:base"))
  compile(project(":util"))
  compile(project(":crm:obj"))
  compile(project(":msg:aj"))
  compileOnly( "javax.servlet:javax.servlet-api:3.1.0")
  compileOnly("org.apache.tomcat:tomcat-websocket-api:9.0.0.M19")
}

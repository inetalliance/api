description = "CG2 Sonar"
apply(plugin = "war")
dependencies {
    compile(project(":obj"))
    compile("net.inetalliance.angular:base:1.1-SNAPSHOT")
    compile("net.inetalliance:util:1.1-SNAPSHOT")
    compile("net.inetalliance.msg:aj:1.1-SNAPSHOT")
    compileOnly("javax.servlet:javax.servlet-api:3.1.0")
    compileOnly("org.apache.tomcat:tomcat-websocket-api:9.0.0.M19")
}

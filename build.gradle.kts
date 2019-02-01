description = "CG2 Sonar"
apply(plugin = "war")
dependencies {
    compile(project(":obj"))
    compile("net.inetalliance.angular:base:1.1-SNAPSHOT")
    compile("net.inetalliance:util:1.1-SNAPSHOT")
    compile("net.inetalliance.msg:aj:1.1-SNAPSHOT")
    compileOnly("javax.servlet:javax.servlet-api:3.1.0")
    compileOnly("javax.websocket:javax.websocket-api:1.1")
}

package net.inetalliance.sonar.events;

import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/events", configurator = Events.Configurator.class)
public class Events
    extends net.inetalliance.angular.events.Events {

}

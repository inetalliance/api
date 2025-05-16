package net.inetalliance.sonar.events;

import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/events", configurator = Events.Configurator.class)
public class Events
        extends net.inetalliance.angular.events.Events {

}

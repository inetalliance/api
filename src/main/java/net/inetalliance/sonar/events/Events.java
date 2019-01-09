package net.inetalliance.sonar.events;

import com.callgrove.obj.Agent;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.impl.AuthorizedUser;

import javax.servlet.http.HttpSession;
import javax.websocket.*;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.$;

@ServerEndpoint(value = "/events", configurator = Events.Configurator.class)
public class Events extends Endpoint {
  private final static Lock lock = new ReentrantLock();
  private static Map<String, Collection<Session>> sessions = new HashMap<>();


  @Override
  public void onClose(final Session session, final CloseReason closeReason) {
    final AuthorizedUser user = getUser(session);
    lock.lock();
    try {
      sessions.getOrDefault(user.getPhone(), new ArrayList<>()).remove(session);
      log.trace("%s disconnected", user.getLastNameFirstInitial());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onOpen(final Session session, final EndpointConfig config) {
    AuthorizedUser user = getUser(session);
    lock.lock();
    try {
      sessions.getOrDefault(user.getPhone(), new ArrayList<>(1)).add(session);
    } finally {
      lock.unlock();
    }
    log.trace("%s connected", user.getLastNameFirstInitial());
    session.addMessageHandler(new SessionHandler(session));
  }

  static AuthorizedUser getUser(final Session session) {
    return (AuthorizedUser) session.getUserProperties().get("authorized");
  }

  public static Agent getAgent(final Session session) {
    return $(new Agent(getUser(session).getPhone()));
  }

  public static void init() {
    SessionHandler.init();
  }

  public static void destroy() {
    SessionHandler.destroy();
    sessions.clear();
  }

  static void send(final Session session, final String type, final Json msg) {
    if (msg != null) {
      try {
        if (session.isOpen()) {
          session.getBasicRemote().sendText(Json.ugly(new JsonMap()
              .$("type", type)
              .$("msg", msg)));
        }
      } catch (IOException e) {
        log.debug("cannot write to closed session for %s", getUser(session));
      }
    }
  }

  static void broadcast(final String type, final String agent, final Json msg) {
    lock.lock();
    try {
      if (agent == null) {
        // tell everyone
        sessions.values()
            .stream()
            .flatMap(Funky::stream)
            .forEach(session -> send(session, type, msg));
      } else {
        // tell only the sockets for that agent
        sessions.get(agent).forEach(session -> send(session, type, msg));
      }
    } finally {
      lock.unlock();
    }
  }

  public static Set<String> getActiveAgents() {
    lock.lock();
    try {
      return new HashSet<>(sessions.keySet());
    } finally {
      lock.unlock();
    }
  }

  public static class Configurator extends ServerEndpointConfig.Configurator {
    @Override
    public void modifyHandshake(final ServerEndpointConfig config, final HandshakeRequest request, final HandshakeResponse response) {
      final HttpSession session = (HttpSession) request.getHttpSession();
      config.getUserProperties().put("authorized", session.getAttribute("authorized"));
    }
  }

  private static final transient Log log = getInstance(Events.class);

}



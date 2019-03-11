package net.inetalliance.sonar.events;

import static com.callgrove.obj.Agent.isActive;
import static com.callgrove.types.CallDirection.INTERNAL;
import static com.callgrove.types.CallDirection.OUTBOUND;
import static com.callgrove.types.CallDirection.QUEUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.potion.Locator.forEach;

import com.callgrove.DaemonThreadFactory;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.types.CallDirection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.websocket.Session;
import net.inetalliance.log.Log;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.asteriskjava.live.AsteriskChannel;

public class HudHandler
    implements Runnable, MessageHandler {

  private static final Map<String, HudStatus> status;
  private static final Pattern sip = Pattern.compile("SIP/(7[0-9][0-9][0-9]).*");
  private static final transient Log log = Log.getInstance(HudHandler.class);

  static {
    status = new HashMap<>();
  }

  private final JsonMap hud;
  private final Set<Session> subscribers;
  private final ExecutorService service = Executors.newFixedThreadPool(4, DaemonThreadFactory.$);
  private ScheduledExecutorService scheduler = Executors
      .newSingleThreadScheduledExecutor(DaemonThreadFactory.$);

  HudHandler() {
    subscribers = Collections.synchronizedSet(new HashSet<>(8));
    hud = new JsonMap(true);
    scheduler.scheduleWithFixedDelay(this, 0, 250, MILLISECONDS);

  }

  static HudStatus getStatus(final String agent) {
    return status.get(agent);

  }

  @Override
  public JsonMap onMessage(final Session session, final JsonMap msg) {
    try {
      final Action action = Action.valueOf(msg.get("action").toUpperCase());
      switch (action) {
        case SUBSCRIBE:
          subscribers.add(session);
          return hud;
        case UNSUBSCRIBE:
          subscribers.remove(session);
      }
    } catch (IllegalArgumentException e) {
      log.error(e);
    }
    return null;
  }

  @Override
  public void destroy() {
    hud.clear();
    scheduler.shutdownNow();
  }

  @Override
  public void run() {
    if (Startup.pbx != null) {
      try {
        updateChannels();
      } catch (final Throwable t) {
        log.error(t);
      }
    }
    updateAvailability();
    updateSimulated();
    JsonMap current = new JsonMap();
    for (final Map.Entry<String, HudStatus> entry : status.entrySet()) {
      current.put(entry.getKey(),
          new JsonMap().$("direction", entry.getValue().direction)
              .$("available", entry.getValue().available));
    }

    if (!current.equals(hud)) {
      hud.clear();
      hud.putAll(current);
      broadcast(new JsonMap().$("type", "hud").$("msg", this.hud));
    }
  }

  private void updateChannels() {
    if (Startup.pbx == null) {
      return;
    }
    final Set<String> linkedChannels = new HashSet<>();

    for (final AsteriskChannel channel : Startup.pbx.getChannels()) {
      if (!linkedChannels.contains(channel.getId())) {
        final AsteriskChannel linkedChannel = channel.getLinkedChannel();
        final boolean linked = linkedChannel != null;
        if (linked) {
          linkedChannels.add(linkedChannel.getId());
        }
        final AsteriskChannel originatingChannel;
        final AsteriskChannel dialedChannel;
        if (linkedChannel == null) {
          originatingChannel = channel;
          dialedChannel = null;
        } else if (channel.getId().compareTo(linkedChannel.getId()) < 0) {
          originatingChannel = channel;
          dialedChannel = linkedChannel;
        } else {
          originatingChannel = linkedChannel;
          dialedChannel = channel;
        }
        updateChannel(originatingChannel, dialedChannel);
      }
    }
  }


  private void updateAvailability() {
    forEach(isActive, agent -> {
      HudStatus hudStatus = status.computeIfAbsent(agent.key, k -> new HudStatus());
      if (agent.isPaused() == hudStatus.available) {
        hudStatus.available = !agent.isPaused();
      }
    });
  }

  private void updateSimulated() {
    forEach(Call.simulated.and(Call.isActive), call -> {
      final Agent agent = call.getActiveAgent();
      final HudStatus agentStatus = status.computeIfAbsent(agent.key, k -> new HudStatus());
      agentStatus.direction = QUEUE;
      agentStatus.callId = call.key;
      agentStatus.available = !agent.isPaused();
    });
  }

  private void updateChannel(final AsteriskChannel originatingChannel,
      final AsteriskChannel dialedChannel) {
    final String agent;
    final CallDirection direction;

    final Matcher oM = sip.matcher(originatingChannel.getName());
    if (oM.matches()) {
      agent = oM.group(1);
      if (dialedChannel == null) {
        direction = OUTBOUND;
      } else {
        final Matcher dM = sip.matcher(dialedChannel.getName());
        direction = dM.matches() ? INTERNAL : OUTBOUND;
        if (direction == INTERNAL) // add both sides of internal calls
        {
          final String otherAgent = dM.group(1);
          final HudStatus agentStatus = status.computeIfAbsent(otherAgent, k -> new HudStatus());
          agentStatus.direction = INTERNAL;
          agentStatus.callId = dialedChannel.getId();
        }
      }
    } else if (dialedChannel != null) {
      final Matcher dM = sip.matcher(dialedChannel.getName());
      agent = dM.matches() ? dM.group(1) : null;
      direction = QUEUE;
    } else {
      agent = null;
      direction = null;
    }
    if (agent != null) {
      final HudStatus agentStatus = status.computeIfAbsent(agent, k -> new HudStatus());
      agentStatus.direction = direction;
      agentStatus.callId = originatingChannel.getId();
    }
  }

  private void broadcast(final JsonMap msg) {
    final CountDownLatch latch = new CountDownLatch(subscribers.size());
    final Collection<Session> toRemove = new ArrayList<>(0);
    try {
      for (final Session subscriber : subscribers) {
        service.submit(() -> {
          try {
            subscriber.getBasicRemote().sendText(Json.ugly(msg));
          } catch (IOException e) {
            toRemove.add(subscriber);
          } finally {
            latch.countDown();
          }
        });
      }
      latch.await(1, SECONDS);
    } catch (InterruptedException e) {
      // oh well, we'll get 'em next time
    } finally {
      // remove any dead ones we found
      subscribers.removeAll(toRemove);
    }
  }

  enum Action {
    SUBSCRIBE,
    UNSUBSCRIBE
  }

}

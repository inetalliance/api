package net.inetalliance.sonar.events;

import com.ameriglide.phenix.core.Log;
import com.callgrove.jobs.Hud;
import jakarta.websocket.Session;
import lombok.val;
import net.inetalliance.angular.events.Events;
import net.inetalliance.cron.CronJob;
import net.inetalliance.cron.CronStatus;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.synchronizedMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.cron.Cron.interval;

public class StatusHandler
        implements MessageHandler {

    private static final Map<String, Boolean> paused = synchronizedMap(
            new LazyMap<>(new HashMap<>(), _ -> false));
    private static final Map<String, Boolean> forwarded = synchronizedMap(
            new LazyMap<>(new HashMap<>(), _ -> false));

    private static final Map<String, Boolean> registered = synchronizedMap(
            new LazyMap<>(new HashMap<>(), _ -> false));
    private static final Map<String, String> calls = synchronizedMap(new HashMap<>());
    private static final Log log = new Log();

    public StatusHandler() {
        super();
        interval(1, SECONDS, new CronJob() {
            @Override
            public String getName() {
                return "Status Updater";
            }

            @Override
            public void exec(final CronStatus status) {
                val map = new JsonMap();
                for (val agent : Events.getActiveAgents()) {
                    getStatus(agent, map, false);
                    if (!map.isEmpty()) {
                        if (map.containsKey("callId")) {
                            Events.sendToLatest("pop", agent, new JsonMap().$("callId", map.get("callId")));
                        }
                        Events.broadcast("status", agent, map);
                    }
                    map.clear();
                }
            }
        });
    }

    private static JsonMap getStatus(final String agent, final JsonMap map, final boolean full) {
        val currentStatus = Hud.currentStatus.getMap(agent);

        check("paused", agent, currentStatus, paused, true, map, full);
        check("forwarded", agent, currentStatus, forwarded, false, map, full);
        check("registered", agent, currentStatus, registered, true, map, full);

        val hudStatus = HudHandler.getStatus(agent);
        if (hudStatus == null) {
            log.warn(() -> "could not get status for %s".formatted(agent));
            return null;
        }
        val currentCall = hudStatus.callId;
        val cachedCall = calls.get(agent);
        if (full || !Objects.equals(currentCall, cachedCall)) {
            map.put("callId", currentCall);
            calls.put(agent, currentCall);
        }

        return map.isEmpty() ? null : map;
    }

    private static void check(final String property, final String agent, final JsonMap current,
                              final Map<String, Boolean> cache, final boolean defaultValue, final JsonMap changes,
                              final boolean full) {
        val currentValue = get(current, property, defaultValue);
        final boolean cachedValue = cache.get(agent);
        val changed = currentValue != cachedValue;

        if (changed || full) {
            changes.put(property, currentValue);
        }
        if (changed) {
            cache.put(agent, currentValue);
        }
    }

    private static boolean get(final JsonMap map, final String key, final boolean defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        val value = map.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private static JsonMap getStatus(final String agent) {
        return getStatus(agent, new JsonMap(), true);
    }

    @Override
    public JsonMap onMessage(final Session session, final JsonMap map) {
        val agent = SessionHandler.getAgent(session);
        val hud = Hud.currentStatus.getMap(agent.key);
        switch (Action.valueOf(map.get("action").toUpperCase())) {
            case PAUSE:
                Locator.update(agent, agent.key, copy -> {
                    copy.setPaused(!copy.isPaused());
                });
                paused.put(agent.key, agent.isPaused());
                if (hud != null) {
                    hud.put("paused", agent.isPaused());
                    Hud.currentStatus.set(agent.key, hud);
                    log.info("%s changed %s to paused: %s", agent.getLastNameFirstInitial(),
                            agent.getLastNameFirstInitial(),
                            agent.isPaused());
                }
                Events.broadcast("status", agent.key, hud);
                return new JsonMap().$("paused", agent.isPaused());
            case FORWARD:
                Locator.update(agent, agent.key, copy -> {
                    copy.setForwarded(!copy.isForwarded());
                });
                forwarded.put(agent.key, agent.isForwarded());
                if (hud != null) {
                    hud.put("forwarded", agent.isForwarded());
                    Hud.currentStatus.set(agent.key, hud);
                    Events.broadcast("status", agent.key, hud);
                    log.info("%s changed %s to forwarded: %s", agent.getLastNameFirstInitial(),
                            agent.getLastNameFirstInitial(),
                            agent.isForwarded());
                }
                return new JsonMap().$("forwarded", agent.isForwarded());
        }
        return null;
    }

    @Override
    public JsonMap onConnect(final Session session) {
        return getStatus(Events.getUser(session).getPhone());
    }

    @Override
    public void destroy() {
        calls.clear();
        paused.clear();
        forwarded.clear();
        registered.clear();
    }

    private enum Action {
        PAUSE,
        FORWARD
    }
}

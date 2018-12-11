package net.inetalliance.sonar.events;

import com.callgrove.jobs.Hud;
import com.callgrove.obj.Agent;
import net.inetalliance.cron.CronJob;
import net.inetalliance.cron.CronStatus;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.comparison.EqualTo;
import net.inetalliance.log.Log;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;

import javax.websocket.Session;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Collections.synchronizedMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.cron.Cron.interval;
import static net.inetalliance.potion.Locator.update;

public class StatusHandler implements MessageHandler {
	private static final Map<String, Boolean> paused =
			synchronizedMap(new LazyMap<String, Boolean>(new TreeMap<>()) {
				@Override
				public Boolean create(final String key) {
					return false;
				}
			});
	private static final Map<String, Boolean> forwarded =
			synchronizedMap(new LazyMap<String, Boolean>(new TreeMap<>()) {
				@Override
				public Boolean create(final String key) {
					return false;
				}
			});
	private static final Map<String, Boolean> registered =
			synchronizedMap(new LazyMap<String, Boolean>(new TreeMap<>()) {
				@Override
				public Boolean create(final String key) {
					return false;
				}
			});
	private static final Map<String, String> calls = synchronizedMap(new TreeMap<String, String>());

	public StatusHandler() {
		super();
		interval(1, SECONDS, new CronJob() {
			@Override
			public String getName() {
				return "Status Updater";
			}

			@Override
			public void exec(final CronStatus status)
					throws Throwable {
				final JsonMap map = new JsonMap();
				for (final String agent : Events.getActiveAgents()) {
					getStatus(agent, map, false);
					if (!map.isEmpty()) {
						Events.broadcast("status", agent, map);
					}
					map.clear();
				}
			}
		});
	}

	private static void check(final String property, final String agent, final JsonMap current,
	                          final Map<String, Boolean> cache, final boolean defaultValue, final JsonMap changes,
	                          final boolean full) {
		final boolean currentValue = get(current, property, defaultValue);
		final boolean cachedValue = cache.get(agent);
		final boolean changed = currentValue != cachedValue;

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
		final Boolean value = map.getBoolean(key);
		return value == null ? defaultValue : value;
	}

	private static JsonMap getStatus(final String agent, final boolean full) {
		return getStatus(agent, new JsonMap(), full);
	}

	private static JsonMap getStatus(final String agent, final JsonMap map, final boolean full) {
		final JsonMap currentStatus = Hud.currentStatus.getMap(agent);

		check("paused", agent, currentStatus, paused, true, map, full);
		check("forwarded", agent, currentStatus, forwarded, false, map, full);
		check("registered", agent, currentStatus, registered, true, map, full);

		final HudStatus hudStatus = HudHandler.getStatus(agent);
		final String currentCall = hudStatus.callId;
		final String cachedCall = calls.get(agent);
		if (full || !EqualTo.$(currentCall).$(cachedCall)) {
			map.put("callId", currentCall);
			calls.put(agent, currentCall);
		}

		return map.isEmpty() ? null : map;
	}


	@Override
	public void destroy() {
		calls.clear();
		paused.clear();
		forwarded.clear();
		registered.clear();
	}

	@Override
	public JsonMap onConnect(final Session session) {
		return getStatus(Events.getUser(session).getPhone(), true);
	}

	@Override
	public JsonMap onMessage(final Session session, final JsonMap map) {
		final Agent agent = Events.getAgent(session);
		final JsonMap hud = Hud.currentStatus.getMap(agent.key);
		switch (Action.valueOf(map.get("action").toUpperCase())) {
			case PAUSE:
				update(agent, agent.key, new P1<Agent>() {
					@Override
					public void $(final Agent copy) {
						copy.setPaused(!copy.isPaused());
					}
				});
				paused.put(agent.key, agent.isPaused());
				if (hud != null) {
					hud.put("paused", agent.isPaused());
					Hud.currentStatus.set(agent.key, hud);
					log.info("%s changed %s to paused: %s",
							agent.getLastNameFirstInitial(), agent.getLastNameFirstInitial(), agent.isPaused());
				}
				return new JsonMap().$("paused", agent.isPaused());
			case FORWARD:
				update(agent, agent.key, new P1<Agent>() {
					@Override
					public void $(final Agent copy) {
						copy.setForwarded(!copy.isForwarded());
					}
				});
				forwarded.put(agent.key, agent.isForwarded());
				if (hud != null) {
					hud.put("forwarded", agent.isForwarded());
					Hud.currentStatus.set(agent.key, hud);
					log.info("%s changed %s to forwarded: %s",
							agent.getLastNameFirstInitial(), agent.getLastNameFirstInitial(), agent.isForwarded());
				}
				return new JsonMap().$("forwarded", agent.isForwarded());
		}
		return null;
	}

	private static final transient Log log = Log.getInstance(StatusHandler.class);

	private enum Action {
		PAUSE,
		FORWARD
	}
}

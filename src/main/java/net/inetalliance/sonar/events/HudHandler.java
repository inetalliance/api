package net.inetalliance.sonar.events;

import com.callgrove.DaemonThreadFactory;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.types.CallDirection;
import net.inetalliance.log.Log;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import org.asteriskjava.live.AsteriskChannel;

import javax.websocket.Session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.callgrove.obj.Agent.*;
import static com.callgrove.types.CallDirection.*;
import static java.util.concurrent.TimeUnit.*;
import static net.inetalliance.potion.Locator.*;

public class HudHandler
	implements Runnable,
	           MessageHandler {

	private static final Map<String, HudStatus> status;

	static {
		status = new LazyMap<>(new HashMap<>(), k -> new HudStatus());
	}

	private static final Pattern sip = Pattern.compile("SIP/(7[0-9][0-9][0-9]).*");
	private static final transient Log log = Log.getInstance(HudHandler.class);
	private static final Lock lock = new ReentrantLock();
	private final JsonMap current;
	private final Set<String> linkedChannels;
	private final JsonMap hud;
	private final Set<Session> subscribers;
	private final ExecutorService service = Executors.newFixedThreadPool(4, DaemonThreadFactory.$);
	private Set<String> untouched = new HashSet<>(8);

	public HudHandler() {
		subscribers = new HashSet<>(8);
		linkedChannels = new TreeSet<>();
		current = new JsonMap();
		hud = new JsonMap();
		scheduler.scheduleWithFixedDelay(this, 0, 250, MILLISECONDS);

	}

	public static HudStatus getStatus(final String agent) {
		lock.lock();
		try {
			return status.get(agent);
		} finally {
			lock.unlock();
		}

	}

	@Override
	public JsonMap onMessage(final Session session, final JsonMap msg) {
		try {
			final Action action = Action.valueOf(msg.get("action").toUpperCase());
			switch (action) {
				case SUBSCRIBE:
					lock.lock();
					try {
						subscribers.add(session);
					} finally {
						lock.unlock();
					}
					return hud;
				case UNSUBSCRIBE:
					lock.lock();
					try {
						subscribers.remove(session);
					} finally {
						lock.unlock();
					}
			}
		} catch (IllegalArgumentException e) {
			log.error(e);
		}
		return null;
	}

	@Override
	public void destroy() {
		lock.lock();
		try {
			hud.clear();
			scheduler.shutdownNow();
		} finally {
			lock.unlock();
		}
	}

	private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(DaemonThreadFactory.$);

	@Override
	public void run() {
		if (Startup.pbx != null) {
			try {
				linkedChannels.clear();
				untouched.clear();
				untouched.addAll(status.keySet());
				updateAvailability();
				updateSimulated();
				updateChannels();
				updateSubscribers();
			} catch (final Throwable t) {
				log.error(t);
			}
		}
	}

	private void updateAvailability() {
		forEach(isActive, agent -> {
			HudStatus hudStatus = status.get(agent.key);
			if (agent.isPaused() == hudStatus.available) {
				hudStatus.available = !agent.isPaused();
				untouched.remove(agent.key);
			}
		});
	}

	private void updateChannels() {
		if (Startup.pbx == null) {
			return;
		}
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

	private void updateChannel(final AsteriskChannel originatingChannel, final AsteriskChannel dialedChannel) {
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
					final HudStatus agentStatus = status.get(otherAgent);
					agentStatus.direction = INTERNAL;
					agentStatus.callId = dialedChannel.getId();
					untouched.remove(otherAgent);
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
			final HudStatus agentStatus = status.get(agent);
			agentStatus.direction = direction;
			agentStatus.callId = originatingChannel.getId();
			untouched.remove(agent);
		}
	}

	private void updateSimulated() {
		forEach(Call.simulated.and(Call.isActive), call -> {
			final Agent agent = call.getActiveAgent();
			final HudStatus agentStatus = status.get(agent.key);
			agentStatus.direction = QUEUE;
			agentStatus.callId = call.key;
			untouched.remove(agent.key);
		});
	}

	private void updateSubscribers() {
		lock.lock();
		try {
			for (final String notActive : untouched) {
				status.get(notActive).clear();
			}
			current.clear();
			for (final Map.Entry<String, HudStatus> entry : status.entrySet()) {
				current.put(entry.getKey(),
					new JsonMap()
						.$("direction", entry.getValue().direction)
						.$("available", entry.getValue().available));
			}

			if (!this.hud.equals(current)) {
				this.hud.clear();
				this.hud.putAll(current);
				//broadcast(Events.wrap(type, this.hud));
			}
		} finally {
			lock.unlock();
		}
	}

	private void broadcast(final JsonMap msg) {
		final CountDownLatch latch = new CountDownLatch(subscribers.size());
		final Collection<Session> toRemove = new ArrayList<>(0);
		lock.lock();
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
			lock.unlock();
		}
	}

	enum Action {
		SUBSCRIBE,
		UNSUBSCRIBE
	}

}

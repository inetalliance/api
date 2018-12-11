package net.inetalliance.sonar;

import com.callgrove.Callgrove;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Queue;
import net.inetalliance.angular.LocatorStartup;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.beejax.messages.BeejaxMessageServer;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.MessageServer;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.events.Events;
import net.inetalliance.types.Credentials;
import net.inetalliance.types.struct.maps.LazyMap;
import net.inetalliance.types.util.LocalizedMessages;
import net.inetalliance.types.util.NetUtil;
import net.inetalliance.util.security.auth.Authenticator;
import net.inetalliance.util.security.auth.Authorized;
import org.asteriskjava.live.DefaultAsteriskServer;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Arrays.asList;
import static java.util.Collections.synchronizedMap;
import static net.inetalliance.angular.AngularServlet.getContextParameter;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;

@WebListener
public class Startup
		extends LocatorStartup {

	private static final transient Log log = Log.getInstance(Startup.class);
	public static DefaultAsteriskServer pbx;

	public static final Map<Integer, Set<String>> productLineQueues;
	public static final boolean isSpainDevServer = System.getProperty("spainDevServer") != null;

	private static final F1<Set<String>, Set<String>> queuesForProductLine = new F1<Set<String>, Set<String>>() {
		@Override
		public Set<String> $(final Set<String> productIds) {
			final TreeSet<String> set = new TreeSet<>();
			for (final String productId : productIds) {
				set.addAll(productLineQueues.get(new Integer(productId)));
			}
			return set;
		}
	}.memoize();

	public static Query<Call> callQueryForProductLines(final HttpServletRequest request, final String parameter) {
		final String[] params = request.getParameterValues(parameter);
		if (params == null || params.length == 0) {
			return Query.all(Call.class);
		}
		final Set<String> paramSet = new HashSet<>(asList(params));
		return callQueryForProductLines(paramSet);

	}

	public static Query<Call> callQueryForProductLines(final Set<String> productLineIds) {
		if (productLineIds.isEmpty()) {
			return Query.all(Call.class);
		}
		final Set<String> queues = queuesForProductLine.$(productLineIds);
		if (queues.isEmpty()) {
			return Query.all(Call.class);
		}
		return Call.Q.withQueueIn(queues);
	}

	static {
		productLineQueues = synchronizedMap(new LazyMap<Integer, Set<String>>(new TreeMap<>()) {
			@Override
			public Set<String> create(final Integer integer) {
				return new TreeSet<>();
			}
		});
	}

	public static Agent getAgent(HttpServletRequest request) {
		final Authorized authorized = Auth.getAuthorized(request);
		return authorized == null ? null : $(new Agent(authorized.getPhone()));
	}

	@Override
	protected void register() {
		Callgrove.register();
		final URL url = Startup.class.getResource("/LocalizedMessages.xml");
		if (url != null) {
			LocalizedMessages.add(Locale.US, url);
		} else {
			log.warning("could not locate localized messages");
		}
	}

	@Override
	public void contextInitialized(final ServletContextEvent sce) {
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
		super.contextInitialized(sce);
		final ServletContext context = sce.getServletContext();
		final String asteriskParam = getContextParameter(context, "asterisk");
		try {
			final URI asterisk = new URI(asteriskParam);
			final Credentials credentials = NetUtil.getCredentials(asterisk);
			int port = asterisk.getPort();
			if (port == -1) {
				port = 5038; // use default manager port if not specified
			}

			log.info("Connecting to Asterisk");
			pbx = new DefaultAsteriskServer(asterisk.getHost(), port, credentials.user, credentials.password);
			pbx.setSkipQueues(true);
			Logger.getLogger("org.asteriskjava").setLevel(Level.SEVERE);
			Logger.getLogger("org.asteriskjava.manager.internal.ManagerConnectionImpl").setLevel(Level.OFF);
			Logger.getLogger("org.asteriskjava.live.internal.ChannelManager").setLevel(Level.OFF);
			Logger.getLogger("org.asteriskjava.live.internal.AsteriskServerImpl").setLevel(Level.OFF);
			Logger.getLogger("org.asteriskjava.util.internal.Slf4JLogger").setLevel(Level.OFF);

			log.info("Starting up Asterisk Manager");
			if(!isSpainDevServer) {
        pbx.initialize();
			}
		} catch (URISyntaxException e) {
			log.error("could not parse asterisk parameter as uri: %s", asteriskParam);
		}
		try {
			Callgrove.beejax =
					MessageServer.$(BeejaxMessageServer.class, getContextParameter(context, "beejaxMessageServer"));

		} catch (Throwable t) {
			log.error(t);
			throw new RuntimeException(t);
		}
		log.info("Loading Product Line -> Queue map");
		forEach(Query.all(Queue.class), new P1<Queue>() {
			@Override
			public void $(final Queue queue) {
				final ProductLine productLine = queue.getProductLine();
				if (productLine != null) {
					productLineQueues.get(productLine.id).add(queue.key);
				}
			}
		});
		Events.init();
		log.info("Context Initialized");

	}

	@Override
	protected Authenticator newAuthenticator(final ServletContext context) {
		final String asset = getContextParameter(context, "asset");
		final String authParam = getContextParameter(context, "authenticator");
		try {
			if (authParam == null) {
				log.warning("Proceeding with no authenticator");
			} else {
				return new net.inetalliance.amberjack.messages.Authenticator(new URI(authParam), asset);
			}
		} catch (URISyntaxException e) {
			log.error("could not parse authenticator as uri: %s", authParam, e);
			throw new RuntimeException(e);
		} catch (Throwable t) {
			log.error(t);
			throw new RuntimeException(t);
		}
		return null;
	}

	@Override
	public void contextDestroyed(final ServletContextEvent sce) {
		super.contextDestroyed(sce);
		Events.destroy();
		if (pbx != null) {
			pbx.shutdown();
		}
	}

	public static <T> Set<T> locateParameterValues(final HttpServletRequest request, final String param,
	                                               final Class<T> type) {
		final String[] params = request.getParameterValues(param);
		if (params == null || params.length == 0) {
			return Collections.emptySet();
		}
		final Set<T> set = new HashSet<>(params.length);
		try {
			final Constructor<T> constructor = type.getConstructor(String.class);
			for (final String key : params) {
				try {
					final T t = Locator.$(constructor.newInstance(key));
					if (t == null) {
						throw new NotFoundException("Could not find %s[%s]", type.getSimpleName(), key);
					}
					set.add(t);
				} catch (final InstantiationException | IllegalAccessException | InvocationTargetException e) {
					throw new RuntimeException(e);
				}
			}
		} catch (final NoSuchMethodException e) {
			try {
				final Constructor<T> constructor = type.getConstructor(Integer.class);
				for (final String key : params) {
					final T t = Locator.$(constructor.newInstance(new Integer(key)));
					if (t == null) {
						throw new NotFoundException("Could not find %s[%s]", type.getSimpleName(), key);
					}
					set.add(t);
				}
			} catch (final NoSuchMethodException | InvocationTargetException | InstantiationException |
					IllegalAccessException e1) {
				throw new RuntimeException(e1);
			}
		}
		return set;
	}
}

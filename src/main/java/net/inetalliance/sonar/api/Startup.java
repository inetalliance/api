package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.callgrove.Callgrove;
import com.callgrove.elastix.CallRouter;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.ProductLine;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.val;
import net.inetalliance.angular.LocatorStartup;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.events.Events;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.beejax.messages.BeejaxMessageServer;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.MessageServer;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.events.SessionHandler;
import net.inetalliance.types.LocalizedMessages;
import net.inetalliance.util.security.auth.Authenticator;
import org.asteriskjava.live.DefaultAsteriskServer;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.angular.AngularServlet.getContextParameter;
import static net.inetalliance.potion.Locator.$;

@WebListener
public class Startup
        extends LocatorStartup {


    private static final Log log = new Log();
    private static final Map<Set<String>, Set<String>> queuesForProductLine = new HashMap<>();
    public static DefaultAsteriskServer pbx;
    public static Map<Integer, Set<String>> productLineQueues;

    static Query<Call> callsWithProductLineParameter(final HttpServletRequest request) {
        val params = request.getParameterValues("pl");
        if (params == null || params.length == 0) {
            return Query.all(Call.class);
        }
        final Set<String> paramSet = new HashSet<>(asList(params));
        return callsWithProductLineKeys(paramSet);

    }

    static Query<Call> callsWithProductLines(final Set<ProductLine> productLines) {
        return callsWithProductLineKeys(
                productLines.stream().map(p -> p.id.toString()).collect(toSet()));

    }

    private static Query<Call> callsWithProductLineKeys(final Set<String> productLineIds) {
        if (productLineIds.isEmpty()) {
            return Query.all(Call.class);
        }
        val queues = queuesForProductLine
                .computeIfAbsent(productLineIds, ids -> ids.stream()
                        .map(Integer::valueOf)
                        .map(
                                productLineQueues::get)
                        .flatMap(Iterables::stream)
                        .collect(toSet()));
        if (queues.isEmpty()) {
            return Query.all(Call.class);
        }
        return Call.withQueueIn(queues);
    }

    public static Agent getAgent(HttpServletRequest request) {
        val authorized = Auth.getAuthorized(request);
        return authorized == null ? null : $(new Agent(authorized.getPhone()));
    }

    static <T> Set<T> locateParameterValues(final HttpServletRequest request, final String param,
                                            final Class<T> type) {
        val params = request.getParameterValues(param);
        if (params == null || params.length == 0) {
            return Collections.emptySet();
        }
        final Set<T> set = new HashSet<>(params.length);
        try {
            val constructor = type.getConstructor(String.class);
            for (val key : params) {
                try {
                    val t = Locator.$(constructor.newInstance(key));
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
                val constructor = type.getConstructor(Integer.class);
                for (val key : params) {
                    val t = Locator.$(constructor.newInstance(Integer.valueOf(key)));
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

    @SneakyThrows
    @Override
    protected void register() {
        Callgrove.register();
        val url = Startup.class.getResource("/LocalizedMessages.xml");
        if (url != null) {
            LocalizedMessages.add(Locale.US, Paths.get(url.toURI()));
        } else {
            log.warn(() -> "could not locate localized messages");
        }
    }

    @Override
    public void contextInitialized(final ServletContextEvent sce) {
        super.contextInitialized(sce);

        new Thread(() -> {
            val context = sce.getServletContext();
            val asteriskParam = getContextParameter(context, "asterisk");
            if (Strings.isNotEmpty(asteriskParam) && System.getProperty("noAsterisk") == null) {
                Logger.getLogger("org.asteriskjava.live.internal.ChannelManager").setLevel(Level.OFF);
                Logger.getLogger("org.asteriskjava.manager.internal.EventBuilderImpl").setLevel(Level.OFF);
                try {
                    val asterisk = new URI(asteriskParam);
                    log.info("Connecting to Asterisk");
                    pbx = CallRouter.init(asterisk);
                    log.info("Starting up Asterisk Manager");
                    pbx.initialize();
                } catch (URISyntaxException e) {
                    log.error("could not parse asterisk parameter as uri: %s", asteriskParam);
                }
            }
            SessionHandler.init();
            Events.handler = SessionHandler::new;
            try {
                Callgrove.beejax =
                        MessageServer
                                .$(BeejaxMessageServer.class, getContextParameter(context, "beejaxMessageServer"));

            } catch (Throwable t) {
                log.error(t);
                throw new RuntimeException(t);
            }
            initProductLines();

            log.info("Context Initialized");

        }).start();

    }

    public static void initProductLines() {
        log.info("Loading Product Line -> Queue map");
        productLineQueues = Locator.execute(
                """
                        SELECT q.queue,q.productLine
                        FROM QueueProductLine as q
                        WHERE q.position = (
                            SELECT MIN(position) FROM queueProductLine where queueProductLine.queue=q.queue
                            )""",
                rs -> {
                    var map = new HashMap<Integer, Set<String>>();
                    try {
                        while (rs.next()) {
                            map.computeIfAbsent(rs.getInt(2), p -> new HashSet<>()).add(rs.getString(1));
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return map;
                });
    }

    @Override
    protected Authenticator newAuthenticator(final ServletContext context) {
        val asset = getContextParameter(context, "asset");
        val authParam = getContextParameter(context, "authenticator");
        try {
            if (authParam == null) {
                log.warn(() -> "Proceeding with no authenticator");
            } else {
                return new net.inetalliance.amberjack.messages.Authenticator(new URI(authParam), asset);
            }
        } catch (URISyntaxException e) {
            log.error(() -> "could not parse authenticator as uri: %s".formatted(authParam), e);
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
        SessionHandler.destroy();
        if (pbx != null) {
            pbx.shutdown();
        }
    }
}

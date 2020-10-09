package net.inetalliance.sonar.api;

import com.callgrove.Callgrove;
import com.callgrove.elastix.CallRouter;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.ProductLine;
import net.inetalliance.angular.LocatorStartup;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.events.Events;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.beejax.messages.BeejaxMessageServer;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.MessageServer;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.events.SessionHandler;
import net.inetalliance.types.util.LocalizedMessages;
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

  private static final transient Log log = Log.getInstance(Startup.class);
  private static final Map<Set<String>, Set<String>> queuesForProductLine = new HashMap<>();
  public static DefaultAsteriskServer pbx;
  public static Map<Integer, Set<String>> productLineQueues;

  static {
  }

  static Query<Call> callsWithProductLineParameter(final HttpServletRequest request) {
    final String[] params = request.getParameterValues("pl");
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
    final Set<String> queues = queuesForProductLine
        .computeIfAbsent(productLineIds, ids -> ids.stream()
            .map(Integer::valueOf)
            .map(
                productLineQueues::get)
            .flatMap(Funky::stream)
            .collect(toSet()));
    if (queues.isEmpty()) {
      return Query.all(Call.class);
    }
    return Call.withQueueIn(queues);
  }

  public static Agent getAgent(HttpServletRequest request) {
    final Authorized authorized = Auth.getAuthorized(request);
    return authorized == null ? null : $(new Agent(authorized.getPhone()));
  }

  static <T> Set<T> locateParameterValues(final HttpServletRequest request, final String param,
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
          final T t = Locator.$(constructor.newInstance(Integer.valueOf(key)));
          if (t == null) {
            throw new NotFoundException("Could not find %s[%s]", type.getSimpleName(), key);
          }
          set.add(t);
        }
      } catch (final NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e1) {
        throw new RuntimeException(e1);
      }
    }
    return set;
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
    super.contextInitialized(sce);
    log.info("Suppressing Asterisk Logging");
    Logger.getLogger("org.asteriskjava.live.internal").setLevel(Level.OFF);

    new Thread(() -> {
      final ServletContext context = sce.getServletContext();
      final String asteriskParam = getContextParameter(context, "asterisk");
      if (StringFun.isNotEmpty(asteriskParam) && System.getProperty("noAsterisk") == null) {
        try {
          final URI asterisk = new URI(asteriskParam);
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
        "SELECT q.queue,q.productline FROM QueueProductLine as q WHERE q.position = (SELECT MIN(position) FROM "
            +
            "queueProductLine where queueProductLine.queue=q.queue)",
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
    SessionHandler.destroy();
    if (pbx != null) {
      pbx.shutdown();
    }
  }
}

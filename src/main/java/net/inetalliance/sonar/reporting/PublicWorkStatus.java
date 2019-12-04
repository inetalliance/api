package net.inetalliance.sonar.reporting;

import static com.callgrove.Callgrove.simple;
import static com.callgrove.obj.Event.inInterval;
import static com.callgrove.obj.Event.withAgent;
import static com.callgrove.obj.Event.withType;
import static com.callgrove.types.EventType.FORWARD;
import static com.callgrove.types.EventType.LOGOFF;
import static com.callgrove.types.EventType.LOGON;
import static com.callgrove.types.EventType.REGISTER;
import static com.callgrove.types.EventType.UNFORWARD;
import static com.callgrove.types.EventType.UNREGISTER;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;

import com.callgrove.obj.Agent;
import com.callgrove.types.EventType;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

@WebServlet("/publicWorkStatus")
public class PublicWorkStatus
    extends AngularServlet {

  private static final transient Log log = Log.getInstance(PublicWorkStatus.class);
  private RedisJsonCache cache;

  @Override
  public void init()
      throws ServletException {
    super.init();
    cache = new RedisJsonCache("public-work-status");
  }

  @Override
  protected void get(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    final String date = request.getParameter("date");
    if (isEmpty(date)) {
      throw new BadRequestException("no date provided");
    }
    final DateMidnight day = simple.parseDateTime(date).toDateMidnight();
    final Map<String, State> state = new TreeMap<>();
      final JsonMap json = new JsonMap();

      /*
    var fields = Funky.stream(manager.getManagedCallCenters(null))
        .map(c -> $$(Agent.withCallCenter(c))).flatMap(Funky::stream)
        .sorted(Comparator.comparing(Agent::getFullName)).forEach
       */
    Set.of("7220", "7501", "7108").stream()
        .map(key -> $(new Agent(key))).forEach(manager -> {
          /* look up initial states */
          final String cacheKey = format("%s:%s", date, manager.key);
          JsonMap cacheResult = cache.getMap(cacheKey);
          if (cacheResult == null) {
            log.debug("Generating data for %s", manager.getFullName());
            final JsonMap callCenterJson = new JsonMap();

            Funky.stream(manager.getManagedCallCenters(null)).map(c->$$(Agent.withCallCenter(c).and(Agent.isActive.and(Agent.isSales))))
                .flatMap(Funky::stream).sorted(Comparator.comparing(Agent::getFullName)).forEach(
                agent -> {
                  log.debug("Agent: %s", agent.getFirstNameLastInitial());
                  final EventType logonChange = agent.getLastLogonChange(day);
                  final EventType forwardChange = agent.getLastForwardChange(day);
                  final EventType registrationChange = agent.getLastRegistrationChange(day);
                  JsonList statusList = new JsonList();
                  callCenterJson
                      .put(agent.getFirstName(), new JsonMap().$("name", agent.getFirstName())
                          .$("status", statusList));
                  final State i = new State();
                  i.paused = logonChange == null || logonChange == LOGOFF;
                  i.forwarded = forwardChange == FORWARD;
                  i.registered = registrationChange == null || registrationChange == REGISTER;
                  i.date = day.toDateTime();
                  i.block = 0;
                  i.statusList = statusList;
                  state.put(agent.key, i);
                  forEach(inInterval(day.toInterval()).and(
                      withType(LOGON, LOGOFF, REGISTER, UNREGISTER, FORWARD, UNFORWARD)
                          .and(withAgent(agent))),
                      event -> {
                        final State s = state.get(event.getAgent().key);
                        if (s != null) {
                          int blockTime =
                              (int) new Interval(s.date, event.getDate()).toDuration()
                                  .getStandardMinutes();
                          s.total = s.total + blockTime;
                          s.block = blockTime;
                          if (!s.paused) {
                            s.queueTime += s.block;
                          }
                          s.statusList.add(s.toJson());
                          s.date = event.getDate();
                          switch (event.getEventType()) {
                            case LOGON:
                              s.paused = false;
                              break;
                            case LOGOFF:
                              s.paused = true;
                              break;
                            case FORWARD:
                              s.forwarded = true;
                              break;
                            case UNFORWARD:
                              s.forwarded = false;
                              break;
                            case UNREGISTER:
                              s.registered = false;
                              break;
                            case REGISTER:
                              s.registered = true;
                              break;
                          }
                        }
                      });
                });
            // add terminal block
            for (State s : state.values()) {
              s.block = 1440 - s.total;
              if (s.block > 0) {
                if (!s.paused) {
                  s.queueTime += s.block;
                }
                s.statusList.add(s.toJson());
              }
              ((JsonMap) s.statusList.get(0)).$("queueTime", s.queueTime);
            }
            state.clear();
            if (day.plusDays(1).isAfterNow()) {
              cache.set(cacheKey, callCenterJson, 15, MINUTES);
            } else {
              cache.set(cacheKey, callCenterJson);
            }
            cacheResult = callCenterJson;
          }
          if (cacheResult.size() > 0) {
            json.put(manager.getFirstName(), cacheResult);
          }
        });

      respond(response, new JsonMap().$("data", json));


  }

  private static class State {

    private int total;
    private boolean forwarded;
    private boolean paused;
    private boolean registered;
    private DateTime date;
    private int block;
    private int queueTime;
    private JsonList statusList;

    private Json toJson() {
      return new JsonMap().$("time", date)
          .$("block", block)
          .$("forwarded", forwarded)
          .$("paused", paused)
          .$("registered", registered)
          .$("status", getClassNames());
    }

    private String getClassNames() {

      if (forwarded) {
        return paused ? "blue mobile" : "orange mobile";
      }
      if (registered) {
        return paused ? "blue" : "orange";
      }
      return "unavailable";

    }
  }

}

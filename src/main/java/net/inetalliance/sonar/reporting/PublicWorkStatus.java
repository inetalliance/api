package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Agent;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.simple;
import static com.callgrove.obj.Event.*;
import static com.callgrove.types.EventType.*;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/publicWorkStatus")
public class PublicWorkStatus
        extends AngularServlet {

    private static final Log log = new Log();
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
        val date = request.getParameter("date");
        if (isEmpty(date)) {
            throw new BadRequestException("no date provided");
        }
        val day = simple.parse(date, LocalDate::from);
        final Map<String, State> state = new TreeMap<>();
        val json = new JsonMap();

      /*
    var fields = Funky.stream(manager.getManagedCallCenters(null))
        .map(c -> $$(Agent.withCallCenter(c))).flatMap(Funky::stream)
        .sorted(Comparator.comparing(Agent::getFullName)).forEach
       */
        Set.of("7220", "7501", "7147").stream()
                .map(key -> $(new Agent(key))).forEach(manager -> {
                    /* look up initial states */
                    val cacheKey = format("%s:%s", date, manager.key);
                    var cacheResult = cache.getMap(cacheKey);
                    if (cacheResult == null) {
                        log.debug("Generating data for %s", manager.getFullName());
                        val callCenterJson = new JsonMap();

                        Iterables.stream(manager.getManagedCallCenters(null)).map(c -> $$(Agent.withCallCenter(c).and(Agent.isActive.and(Agent.isSales))))
                                .flatMap(Iterables::stream).sorted(Comparator.comparing(Agent::getFullName)).forEach(
                                        agent -> {
                                            log.debug("Agent: %s", agent.getFirstNameLastInitial());
                                            var statusList = new JsonList();
                                            callCenterJson
                                                    .put(agent.getFirstName(), new JsonMap().$("name", agent.getFirstName())
                                                            .$("status", statusList));
                                            val i = getState(agent, day, statusList);
                                            state.put(agent.key, i);

                                        });
                        // add terminal block
                        addTerminalBlock(state.values());
                        state.clear();
                        if (day.plusDays(1).isAfter(LocalDate.now())) {
                            cache.set(cacheKey, callCenterJson, 15, MINUTES);
                        } else {
                            cache.set(cacheKey, callCenterJson);
                        }
                        cacheResult = callCenterJson;
                    }
                    if (!cacheResult.isEmpty()) {
                        json.put(manager.getFirstName(), cacheResult);
                    }
                });

        respond(response, new JsonMap().$("data", json));


    }

    protected static void addTerminalBlock(Collection<State> state) {
        for (var s : state) {
            s.block = 1440 - s.total;
            if (s.block > 0) {
                if (!s.paused) {
                    s.queueTime += s.block;
                }
                s.statusList.add(s.toJson());
            }
            ((JsonMap) s.statusList.getFirst()).$("queueTime", s.queueTime);
        }
    }

    protected static State getState(Agent agent, LocalDate day, JsonList statusList) {
        val logonChange = agent.getLastLogonChange(day.atStartOfDay());
        val forwardChange = agent.getLastForwardChange(day.atStartOfDay());
        val registrationChange = agent.getLastRegistrationChange(day.atStartOfDay());
        val i = new State();
        i.paused = logonChange == null || logonChange == LOGOFF;
        i.forwarded = forwardChange == FORWARD;
        i.registered = registrationChange == null || registrationChange == REGISTER;
        i.date = day.atStartOfDay();
        i.block = 0;
        i.statusList = statusList;
        forEach(inInterval(new DateTimeInterval(day)).and(
                        withType(LOGON, LOGOFF, REGISTER, UNREGISTER, FORWARD, UNFORWARD)
                                .and(withAgent(agent))),
                event -> {
                    var blockTime =
                            (int) new DateTimeInterval(i.date, event.getDate()).toDuration()
                                    .toMinutes();
                    i.total = i.total + blockTime;
                    i.block = blockTime;
                    if (!i.paused) {
                        i.queueTime += i.block;
                    }
                    i.statusList.add(i.toJson());
                    i.date = event.getDate();
                    switch (event.getEventType()) {
                        case LOGON:
                            i.paused = false;
                            break;
                        case LOGOFF:
                            i.paused = true;
                            break;
                        case FORWARD:
                            i.forwarded = true;
                            break;
                        case UNFORWARD:
                            i.forwarded = false;
                            break;
                        case UNREGISTER:
                            i.registered = false;
                            break;
                        case REGISTER:
                            i.registered = true;
                            break;
                    }
                });
        return i;
    }

    protected static class State {

        private int total;
        private boolean forwarded;
        private boolean paused;
        private boolean registered;
        private LocalDateTime date;
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

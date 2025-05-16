package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Agent;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.simple;
import static com.callgrove.obj.Agent.withCallCenter;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/workStatus")
public class WorkStatus
        extends AngularServlet {

    private static final Log log = new Log();
    private RedisJsonCache cache;

    @Override
    public void init()
            throws ServletException {
        super.init();
        cache = new RedisJsonCache("work-status");
    }

    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        val date = request.getParameter("date");
        if (isEmpty(date)) {
            throw new BadRequestException("no date provided");
        }
        val day = simple.parse(date, LocalDate::from);
        val loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        final Map<String, PublicWorkStatus.State> state = new TreeMap<>();
        if (loggedIn.isManager() || loggedIn.isTeamLeader()) {
            val json = new JsonMap();
            /* look up initial states */
            for (val callCenter : loggedIn.getViewableCallCenters()) {
                val cacheKey = format("%s:%d", date, callCenter.id);
                var cacheResult = cache.getMap(cacheKey);
                if (cacheResult == null) {
                    log.debug("Generating data for %s", callCenter.getName());
                    val callCenterJson = new JsonMap();
                    forEach(withCallCenter(callCenter).and(Agent.isSales).and(Agent.isActive)
                                    .orderBy("firstName", ASCENDING),
                            agent -> {
                                log.debug("Agent: %s", agent.getFirstNameLastInitial());
                                var statusList = new JsonList();
                                callCenterJson
                                        .put(agent.key, new JsonMap().$("name", agent.getFirstNameLastInitial())
                                                .$("status", statusList));
                                val i = PublicWorkStatus.getState(agent, day, statusList);
                                state.put(agent.key, i);
                            });
                    // add terminal block
                    PublicWorkStatus.addTerminalBlock(state.values());
                    state.clear();
                    if (day.plusDays(1).isAfter(LocalDate.now())) {
                        cache.set(cacheKey, callCenterJson, 15, MINUTES);
                    } else {
                        cache.set(cacheKey, callCenterJson);
                    }
                    cacheResult = callCenterJson;
                }
                if (!cacheResult.isEmpty()) {
                    json.put(callCenter.getName(), cacheResult);
                }
            }

            respond(response, new JsonMap().$("data", json));

        } else {
            throw new ForbiddenException("%s attempted to access the work status report",
                    loggedIn.getLastNameFirstInitial());
        }

    }


}

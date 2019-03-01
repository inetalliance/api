package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.log.*;
import net.inetalliance.potion.cache.*;
import net.inetalliance.sonar.api.*;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.*;
import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.util.*;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Agent.*;
import static com.callgrove.obj.Event.*;
import static com.callgrove.types.EventType.*;
import static java.lang.String.*;
import static java.util.concurrent.TimeUnit.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/reporting/reports/workStatus")
public class WorkStatus
		extends AngularServlet {

	private static final transient Log log = Log.getInstance(WorkStatus.class);
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
		final String date = request.getParameter("date");
		if (isEmpty(date)) {
			throw new BadRequestException("no date provided");
		}
		final DateMidnight day = simple.parseDateTime(date).toDateMidnight();
		final Agent loggedIn = Startup.getAgent(request);
		assert loggedIn != null;
		final Map<String, State> state = new TreeMap<>();
		if (loggedIn.isManager() || loggedIn.isTeamLeader()) {
			final JsonMap json = new JsonMap();
			/* look up initial states */
			for (final CallCenter callCenter : loggedIn.getViewableCallCenters()) {
				final String cacheKey = format("%s:%d", date, callCenter.id);
				JsonMap cacheResult = cache.getMap(cacheKey);
				if (cacheResult == null) {
					log.debug("Generating data for %s", callCenter.getName());
					final JsonMap callCenterJson = new JsonMap();
					forEach(withCallCenter(callCenter).and(Agent.isSales).and(Agent.isActive).orderBy("firstName", ASCENDING),
					        agent -> {
						        log.debug("Agent: %s", agent.getFirstNameLastInitial());
						        final EventType logonChange = agent.getLastLogonChange(day);
						        final EventType forwardChange = agent.getLastForwardChange(day);
						        final EventType registrationChange = agent.getLastRegistrationChange(day);
						        JsonList statusList = new JsonList();
						        callCenterJson.put(agent.key, new JsonMap().$("name", agent.getFirstNameLastInitial())
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
								        withType(LOGON, LOGOFF, REGISTER, UNREGISTER, FORWARD, UNFORWARD).and(withAgent(agent))),
						                event -> {
							                final State s = state.get(event.getAgent().key);
							                if (s != null) {
								                int blockTime =
										                (int) new Interval(s.date, event.getDate()).toDuration().getStandardMinutes();
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
					json.put(callCenter.getName(), cacheResult);
				}
			}

			respond(response, new JsonMap().$("data", json));

		} else {
			throw new ForbiddenException("%s attempted to access the work status report", loggedIn.getLastNameFirstInitial());
		}

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

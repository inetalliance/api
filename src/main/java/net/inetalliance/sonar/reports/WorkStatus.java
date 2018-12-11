package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.Event;
import com.callgrove.types.EventType;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.log.Log;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.sonar.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.TreeMap;

import static com.callgrove.obj.Agent.Q.withCallCenter;
import static com.callgrove.obj.Event.Q.*;
import static com.callgrove.types.EventType.*;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.funky.functors.types.str.StringFun.empty;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sonar.reports.CachedGroupingRangeReport.simple;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/workStatus")
public class WorkStatus extends AngularServlet {

	private RedisJsonCache cache;

	@Override
	public void init() throws ServletException {
		super.init();
		cache = new RedisJsonCache("work-status");
	}

	private static class State {
		int total;
		boolean forwarded;
		boolean paused;
		boolean registered;
		DateTime date;
		int block;
		int queueTime;
		JsonList statusList;


		private String getClassNames() {

			if (forwarded) {
				return paused ? "blue mobile" : "orange mobile";
			}
			if (registered) {
				return paused ? "blue" : "orange";
			}
			return "unavailable";

		}

		public Json toJson() {
			return new JsonMap()
					.$("time", date)
					.$("block", block)
					.$("forwarded", forwarded)
					.$("paused", paused)
					.$("registered", registered)
					.$("status", getClassNames());
		}
	}

	@Override
	protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
		final String date = request.getParameter("date");
		if (empty.$(date)) {
			throw new BadRequestException("no date provided");
		}
		final DateMidnight day = simple.parseDateTime(date).toDateMidnight();
		final Agent loggedIn = Startup.getAgent(request);
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
					forEach(withCallCenter(callCenter).and(Agent.Q.sales).and(Agent.Q.active).orderBy("firstName", ASCENDING),
							new P1<Agent>() {
								@Override
								public void $(final Agent agent) {
									log.debug("Agent: %s", agent.getFirstNameLastInitial());
									final EventType logonChange = agent.getLastLogonChange(day);
									final EventType forwardChange = agent.getLastForwardChange(day);
									final EventType registrationChange = agent.getLastRegistrationChange(day);
									JsonList statusList = new JsonList();
									callCenterJson.put(agent.key, new JsonMap()
											.$("name", agent.getFirstNameLastInitial())
											.$("status", statusList));
									final State i = new State();
									i.paused = logonChange == null || logonChange == LOGOFF;
									i.forwarded = forwardChange != null && forwardChange == FORWARD;
									i.registered = registrationChange == null || registrationChange == REGISTER;
									i.date = day.toDateTime();
									i.block = 0;
									i.statusList = statusList;
									state.put(agent.key, i);
									forEach(inInterval(day.toInterval())
													.and(withType(LOGON, LOGOFF, REGISTER, UNREGISTER, FORWARD, UNFORWARD)
															.and(withAgent(agent))),
											new P1<Event>() {
												@Override
												public void $(final Event event) {
													final State s = state.get(event.getAgent().key);
													if (s != null) {
														int blockTime = (int) new Interval(s.date, event.getDate()).toDuration().getStandardMinutes();
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
												}
											});
								}
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
			throw new ForbiddenException("%s attempted to access the work status report",
					loggedIn.getLastNameFirstInitial());
		}

	}

	private static final transient Log log = Log.getInstance(WorkStatus.class);

}

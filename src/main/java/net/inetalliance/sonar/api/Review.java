package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.CallerId;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.SortedQuery;
import net.inetalliance.types.Duration;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Call.isActive;
import static com.callgrove.obj.Call.withAgentIn;
import static com.callgrove.obj.Call.*;
import static com.callgrove.obj.Opportunity.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/review")
public class Review
	extends AngularServlet {

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final Agent loggedIn = Startup.getAgent(request);
		assert loggedIn != null;
		final String callCenterParam = request.getParameter("callCenter");
		final CallCenter callCenter;
		if (isEmpty(callCenterParam)) {
			if (loggedIn.isManager() || loggedIn.getManaged() != null) {
				final Iterator<CallCenter> iterator = loggedIn.getViewableCallCenters().iterator();
				callCenter = iterator.hasNext() ? iterator.next() : null;
			} else {
				callCenter = null;
			}
		} else {
			callCenter = Locator.$(new CallCenter(Integer.valueOf(callCenterParam)));
			if (callCenter == null) {
				throw new NotFoundException("Could not find call center %s", callCenterParam);
			}
			if (!loggedIn.isManager() && !callCenter.equals(loggedIn.getManaged())) {
				throw new ForbiddenException("%s tried to access call center %s", loggedIn.getLastNameFirstInitial(),
					callCenter.getName());
			}
		}

		final Set<Agent> viewableAgents = callCenter == null
			? Collections.singleton(loggedIn)
			: $$(Agent.isActive.and(Agent.withCallCenter(callCenter)));
		if (viewableAgents.isEmpty()) {
			throw new BadRequestException("%s does not have any viewable agents", loggedIn.getLastNameFirstInitial());
		}
		final String dayParam = request.getParameter("date");
		try {
			final DateMidnight day = isEmpty(dayParam)
				? new DateMidnight()
				: simple.parseDateTime(dayParam).toDateMidnight();
			final Query<Call> q = isQueue.and(isActive.negate()).and(inInterval(day.toInterval())).and(withAgentIn
				(viewableAgents));
			final Collection<Json> json = new ArrayList<>(count(q));
			forEach(q, call -> {
				final JsonMap map = new JsonMap()
					.$("key")
					.$("created")
					.$("resolution")
					.$("notes")
					.$("todo")
					.$("dumped")
					.$("reviewed")
					.$("silent");
				Info.$(Call.class).fill(call, map);
				final CallerId callerId = call.getCallerId();
				final Site site = call.getSite();
				final Agent agent = call.getAgent();
				map.$("callerId",
					new JsonMap()
						.$("name", callerId == null ? "Unknown" : callerId.getName())
						.$("number", callerId == null ? "" : callerId.getNumber()))
					.$("site", site == null ? null : new JsonMap()
						.$("abbreviation", site.getAbbreviation().toString()))
					.$("agent", agent == null ? null : new JsonMap()
						.$("name", agent.getFirstNameLastInitial())
						.$("key", agent.key))
					.$("queue", call.getQueue().getName())
					.$("productLine", call.getQueue().getProductLine().getName())
					.$("duration", new Duration(call.getDuration().toDurationMillis()).getAbbreviation(true));
				final Agent blame = call.getBlame();
				map.$("blame", blame == null ? null : new JsonMap()
					.$("name", blame.getFirstNameLastInitial())
					.$("key", blame.key));

				final Query<Contact> cQ = Contact.withPhoneNumber(call.getRemoteCallerId().getNumber());
				final JsonList contacts = new JsonList(count(cQ));
				final Map<Agent, Collection<Opportunity>> opps = new HashMap<>();
				forEach(cQ, contact -> {
					final JsonMap map1 = new JsonMap()
						.$("id", contact.id)
						.$("name", contact.getFullName())
						.$("selected", contact.equals(call.getContact()));
					contacts.add(map1);
					forEach(Opportunity.withContact(contact).and(createdBefore(call.getCreated().plusHours(1))),
						o -> opps.computeIfAbsent(o.getAssignedTo(), a -> new ArrayList<>()).add(o));
				});
				map.$("contacts", contacts);
				final SortedQuery<Segment> sQ = Segment.withCall(call);
				final JsonList segments = new JsonList(count(sQ));
				map.$("segments", segments);
				forEach(sQ, segment -> {
					final Agent agent1 = segment.getAgent();
					final JsonMap sMap = new JsonMap()
						.$("created", segment.getCreated())
						.$("answered", segment.getAnswered())
						.$("ended", segment.getEnded())
						.$("talktime", segment.getAnswered() == null ? null : new Duration(segment.getTalkTime()
							.toDurationMillis()).getAbbreviation(true))
						.$("agent", agent1 == null ? null : new JsonMap()
							.$("name", agent1.getFirstNameLastInitial())
							.$("key", agent1.key));
					if (agent1 != null) {
						final JsonList oList = opps.getOrDefault(agent1,Set.of()).stream().map(o -> new JsonMap()
							.$("id", o.id)
							.$("notes", o.getNotes())
							.$("stage", o.getStage())
							.$("productLine", o.getProductLineName())
							.$("estimatedClose", o.getEstimatedClose())
							.$("existing", o.getCreated().isBefore(call.getCreated()))
							.$("reminder", o.getReminder())).collect(Collectors.toCollection(JsonList::new));
						sMap.$("opportunities", oList);
					}
					segments.add(sMap);
				});
				json.add(map);
			});
			respond(response, Listable.formatResult(json));
		} catch (IllegalArgumentException e) {
			log.error(e);
			throw new BadRequestException("Unparseable day specified: %s", dayParam);
		}
	}

	private static final transient Log log = getInstance(Review.class);
}

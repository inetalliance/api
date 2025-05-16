package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.info.Info;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.simple;
import static com.callgrove.obj.Call.*;
import static com.callgrove.obj.Opportunity.createdBefore;
import static java.time.format.FormatStyle.SHORT;
import static java.util.stream.Collectors.joining;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/review")
public class Review
        extends AngularServlet {

    private RedisJsonCache cache;
    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        val callCenterParam = request.getParameter("callCenter");
        final CallCenter callCenter;
        if (isEmpty(callCenterParam)) {
            if (loggedIn.isManager() || loggedIn.getManaged() != null) {
                val iterator = loggedIn.getViewableCallCenters().iterator();
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
                throw new ForbiddenException("%s tried to access call center %s",
                        loggedIn.getLastNameFirstInitial(),
                        callCenter.getName());
            }
        }

        val viewableAgents =
                callCenter == null ? Collections.singleton(loggedIn) :
                        $$(Agent.isActive.and(Agent.withCallCenter(callCenter)));
        if (viewableAgents.isEmpty()) {
            throw new BadRequestException("%s does not have any viewable agents",
                    loggedIn.getLastNameFirstInitial());
        }
        val dayParam = request.getParameter("date");

        try {
            val day = isEmpty(dayParam) ? LocalDate.now() : simple.parse(dayParam, LocalDate::from);
            var cacheKey = String.format("Review:%s,%s", day,
                    viewableAgents.stream().map(a -> a.key).collect(joining("|")));
            val allowCache = !day.plusDays(1).isAfter(LocalDate.now());
            if (allowCache) {
                val cached = cache.getMap(cacheKey);
                if (cached != null) {
                    respond(response, cached);
                    return;
                }
            }
            val q =
                    isQueue.and(isActive.negate()).and(inInterval(new DateTimeInterval(day)))
                            .and(withAgentIn(viewableAgents).or(withBlameIn(viewableAgents)));
            val callCount = count(q);
            val json = new ArrayList<Json>(callCount);
            log.info("There are %d calls to process", callCount);
            var meter = new ProgressMeter(callCount);
            forEach(q, call -> {
                meter.increment();
                val map = new JsonMap().$("key")
                        .$("created")
                        .$("resolution")
                        .$("notes")
                        .$("todo")
                        .$("dumped")
                        .$("reviewed")
                        .$("silent");
                Info.$(Call.class).fill(call, map);
                val callerId = call.getCallerId();
                val site = call.getSite();
                val agent = call.getAgent();
                map.$("callerId", new JsonMap().$("name", callerId == null ? "Unknown" : callerId.getName())
                                .$("number", callerId == null ? "" : callerId.getNumber()))
                        .$("site", site == null ? null
                                : new JsonMap().$("abbreviation", site.getAbbreviation().toString()))
                        .$("agent",
                                agent == null ? null
                                        : new JsonMap().$("name", agent.getFirstNameLastInitial()).$("key", agent.key))
                        .$("queue", call.getQueue().getName())
                        .$("productLine", call.getQueue().getProductLine().getName())
                        .$("duration",
                                DateTimeFormats.ofDuration(SHORT).format(call.getDuration()));
                val blame = call.getBlame();
                map.$("blame",
                        blame == null ? null
                                : new JsonMap().$("name", blame.getFirstNameLastInitial()).$("key", blame.key));

                val cQ = Contact.withPhoneNumber(call.getRemoteCallerId().getNumber())
                        .limit(3);
                val contactsCount = count(cQ);
                val contacts = new JsonList(contactsCount);
                final Map<Agent, Collection<Opportunity>> opps = new HashMap<>();
                forEach(cQ, contact -> {
                    val map1 = new JsonMap().$("id", contact.id)
                            .$("name", contact.getFullName())
                            .$("selected", contact.equals(call.getContact()));
                    contacts.add(map1);
                    forEach(
                            Opportunity.withContact(contact).and(createdBefore(call.getCreated().plusHours(1))),
                            o -> opps.computeIfAbsent(o.getAssignedTo(), a -> new ArrayList<>()).add(o));
                });
                map.$("contacts", contacts);
                val sQ = Segment.withCall(call);
                val segments = new JsonList(count(sQ));
                map.$("segments", segments);
                forEach(sQ, segment -> {
                    val agent1 = segment.getAgent();
                    val sMap = new JsonMap().$("created", segment.getCreated())
                            .$("answered", segment.getAnswered())
                            .$("ended", segment.getEnded())
                            .$("talktime", segment.getTalkTime() == null
                                    ? null
                                    : DateTimeFormats.ofDuration(SHORT).format( segment.getTalkTime().toDuration()))
                            .$("agent", agent1 == null
                                    ? null
                                    : new JsonMap().$("name", agent1.getFirstNameLastInitial())
                                    .$("key", agent1.key));
                    if (agent1 != null) {
                        val oList = opps.getOrDefault(agent1, Set.of())
                                .stream()
                                .map(o -> new JsonMap().$("id", o.id)
                                        .$("notes", o.getNotes())
                                        .$("stage", o.getStage())
                                        .$("productLine", o.getProductLineName())
                                        .$("estimatedClose", o.getEstimatedClose())
                                        .$("existing", o.getCreated().isBefore(call.getCreated()))
                                        .$("reminder", o.getReminder()))
                                .collect(Collectors.toCollection(JsonList::new));
                        sMap.$("opportunities", oList);
                    }
                    segments.add(sMap);
                });
                json.add(map);
            });
            val result = Listable.formatResult(json);
            if (allowCache) {
                cache.set(cacheKey, result);
            }
            respond(response, result);
        } catch (IllegalArgumentException e) {
            log.error(e);
            throw new BadRequestException("Unparseable day specified: %s", dayParam);
        }
    }

    @Override
    public void init() throws ServletException {
        super.init();
        cache = new RedisJsonCache(getClass().getSimpleName());
    }
}

package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Iterables;
import com.callgrove.obj.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.types.SalesStage.HOT;
import static java.util.stream.Collectors.toSet;

@WebServlet("/api/opportunity/*")
public class Opportunities
        extends ListableModel<Opportunity> {

    private static final Map<Integer, Set<Integer>> relatedSites = new HashMap<>();

    public Opportunities() {
        super(Opportunity.class);
    }

    public static Json json(Opportunity arg) {
        val assignedTo = arg.getAssignedTo();
        val productLine = arg.getProductLine();
        val site = arg.getSite();
        val stage = arg.getStage();
        return new JsonMap().$("id", arg.id)
                .$("stage", new JsonMap().$("name", stage.name()).$("ordinal", stage.ordinal()))
                .$("site", new JsonMap().$("id", site.id)
                        .$("beejaxId", site.getBeejaxId())
                        .$("abbreviation", site.getAbbreviation())
                        .$("name", site.getName()))
                .$("assignedTo",
                        new JsonMap().$("name", assignedTo.getLastNameFirstInitial()).$("key", assignedTo.key))
                .$("productLine", new JsonMap().$("name", productLine.getName())
                        .$("abbreviation", productLine.getAbbreviation())
                        .$("id", productLine.id)
                        .$("wizard", productLine.getWizard())
                        .$("root", productLine.getRoot() == null
                                ? null
                                : productLine.getRoot().id));
    }

    public static Set<Integer> relatedSites(Integer id) {
        return relatedSites.computeIfAbsent(id, i -> Locator.$(new Site(id))
                .getSiteGroups()
                .stream()
                .map(SiteGroup::getSites)
                .flatMap(Iterables::stream)
                .map(s -> s.id)
                .collect(toSet()));
    }

    @Override
    public JsonMap create(final Key<Opportunity> key, final HttpServletRequest request,
                          final HttpServletResponse response, final JsonMap data) {
        data.put("created", LocalDateTime.now());
        return super.create(key, request, response, data);
    }

    @Override
    public Query<Opportunity> all(final Class<Opportunity> type, final HttpServletRequest request) {
        var q = super.all(type, request);
        val callId = request.getParameter("call");
        if (isNotEmpty(callId)) {
            val call = Locator.$(new Call(callId));
            if (call == null) {
                throw new NotFoundException("Could not find call with key %s", callId);
            }
            q = q.and(Opportunity.withSiteIdIn(relatedSites(call.getSite().id)));
        }
        val contactId = request.getParameter("contact");
        if (isNotEmpty(contactId)) {
            val contact = Locator.$(new Contact(Integer.valueOf(contactId)));
            if (contact == null) {
                throw new NotFoundException("Could not find contact with id %s", contactId);
            }
            q = q.and(Opportunity.withContact(contact));
        }

        return q;
    }

    @Override
    protected Opportunity lookup(final Key<Opportunity> key, final HttpServletRequest request) {
        if ("0".equals(key.id)) {
            val opp = new Opportunity();
            opp.setStage(HOT);
            opp.setEstimatedClose(LocalDate.now());
            opp.setAssignedTo(Locator.$(new Agent(Auth.getAuthorized(request).getPhone())));
            val callKey = request.getParameter("call");
            if (isEmpty(callKey)) {
                throw new BadRequestException("must specify a call key");
            }
            val call = Locator.$(new Call(callKey));
            if (call == null) {
                throw new NotFoundException("could not find call with key %s", callKey);
            }
            opp.setProductLine(call.getQueue().getProductLine());
            opp.setSource(call.getSource());
            opp.setCreated(LocalDateTime.now());
            opp.setSite(call.getSite());
            return opp;
        }
        return super.lookup(key, request);
    }

    @Override
    protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity,
                          final HttpServletRequest request) {
        val map = (JsonMap) super.toJson(key, opportunity, request);
        map.put("extra", json(opportunity));
        return map;
    }

    @Override
    public Json toJson(final HttpServletRequest request, final Opportunity o) {

        var superJson = (JsonMap) super.toJson(request, o);
        if (request.getParameter("summary") == null) {
            return superJson;
        }
        return superJson.$("extra", json(o));
    }
}

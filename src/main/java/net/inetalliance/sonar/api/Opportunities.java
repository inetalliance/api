package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.SalesStage;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.funky.Funky;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.callgrove.types.SalesStage.HOT;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.funky.StringFun.isNotEmpty;

@WebServlet("/api/opportunity/*")
public class Opportunities
	extends ListableModel<Opportunity> {

	public static Json json(Opportunity arg) {
		final Agent assignedTo = arg.getAssignedTo();
		final ProductLine productLine = arg.getProductLine();
		final Site site = arg.getSite();
		final SalesStage stage = arg.getStage();
		return new JsonMap()
			.$("id", arg.id)
			.$("stage", new JsonMap()
				.$("name", stage.name())
				.$("ordinal", stage.ordinal()))
			.$("site", new JsonMap()
				.$("id", site.id)
				.$("beejaxId", site.getBeejaxId())
				.$("abbreviation", site.getAbbreviation())
				.$("name", site.getName()))
			.$("assignedTo", new JsonMap()
				.$("name", assignedTo.getLastNameFirstInitial())
				.$("key", assignedTo.key))
			.$("productLine", new JsonMap()
				.$("name", productLine.getName())
				.$("abbreviation", productLine.getAbbreviation())
				.$("id", productLine.id)
				.$("wizard", productLine.getWizard())
				.$("root", productLine.getRoot() == null ? null : productLine.getRoot().id));
	}


	@Override
	public JsonMap create(final Key<Opportunity> key, final HttpServletRequest request,
	                      final HttpServletResponse response, final JsonMap data) {
		data.put("created", new DateTime());
		return super.create(key, request, response, data);
	}

	private static Map<Integer, Set<Integer>> relatedSites = new HashMap<>();

	public static Set<Integer> relatedSites(Integer id) {
		return relatedSites.computeIfAbsent(id, i -> Locator.$(new Site(id)).getSiteGroups()
			.stream().map(SiteGroup::getSites).flatMap(Funky::stream).map(s -> s.id).collect(toSet()));
	}


	public Opportunities() {
		super(Opportunity.class);
	}

	@Override
	public Query<Opportunity> all(final Class<Opportunity> type, final HttpServletRequest request) {
		Query<Opportunity> q = super.all(type, request);
		final String callId = request.getParameter("call");
		if (isNotEmpty(callId)) {
			final Call call = Locator.$(new Call(callId));
			if (call == null) {
				throw new NotFoundException("Could not find call with key %s", callId);
			}
			q = q.and(Opportunity.withSiteIdIn(relatedSites(call.getSite().id)));
		}
		final String contactId = request.getParameter("contact");
		if (isNotEmpty(contactId)) {
			final Contact contact = Locator.$(new Contact(Integer.valueOf(contactId)));
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
			final Opportunity opp = new Opportunity();
			opp.setStage(HOT);
			opp.setEstimatedClose(new DateMidnight());
			opp.setAssignedTo(Locator.$(new Agent(Auth.getAuthorized(request).getPhone())));
			final String callKey = request.getParameter("call");
			if (isEmpty(callKey)) {
				throw new BadRequestException("must specify a call key");
			}
			final Call call = Locator.$(new Call(callKey));
			if (call == null) {
				throw new NotFoundException("could not find call with key %s", callKey);
			}
			opp.setProductLine(call.getQueue().getProductLine());
			opp.setSource(call.getSource());
			opp.setCreated(new DateTime());
			opp.setSite(call.getSite());
			return opp;
		}
		return super.lookup(key, request);
	}

	@Override
	protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity, final HttpServletRequest request) {
		final JsonMap map = (JsonMap) super.toJson(key, opportunity, request);
		map.put("extra", json(opportunity));
		return map;
	}

	@Override
	public Json toJson(final HttpServletRequest request, final Opportunity o) {

		JsonMap superJson = (JsonMap) super.toJson(request, o);
		if (request.getParameter("summary") == null) {
			return superJson;
		}
		return superJson.$("extra", json(o));
	}
}

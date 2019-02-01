package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.CallerId;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;

import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.sonar.api.Opportunities.relatedSites;

@WebServlet("/api/pop/*")
public class Pop
	extends TypeModel<Call> {

	public Pop() {
		super(Call.class, Pattern.compile("/api/pop/(.*)"));
	}

	@Override
	protected Json toJson(final Key<Call> key, final Call call, final HttpServletRequest request) {
		final Query<Contact> query;
		final String q = request.getParameter("q");
		final CallerId remoteCid = call.getRemoteCallerId();
		final String cid =
			remoteCid == null || isEmpty(remoteCid.getNumber()) ? null : remoteCid.getNumber();
		if (isNotEmpty(q)) {
			final Collection<SiteGroup> siteGroups = call.getSite().getSiteGroups();
			final Search<Contact> search = new Search<>(Contact.class, getParameter(request, "n", 10), q.split("[ ]"));
			if (siteGroups.isEmpty()) {
				query = search;
			} else {
				query = search.and(Contact.withOppsOn(siteGroups.iterator().next().getSites()));
			}
		} else if (call.getContact() != null) {
			query = Contact.withId(Contact.class, call.getContact().id);
		} else if (cid != null && cid.length() >= 10) {
			query = Contact.withPhoneNumber(remoteCid.getNumber());
		} else {
			query = Query.none(Contact.class);
		}
		final Opportunity[] preferred = new Opportunity[1];
		final JsonList contacts = new JsonList(1);
		final Agent loggedIn = Startup.getAgent(request);
		assert loggedIn != null;
		final Comparator<Opportunity> matchQuality = (a, b) -> {
			if (b == null) {
				return 1;
			}
			// 1st prioritize same site opps
			if (!a.getSite().id.equals(b.getSite().id)) {
				if (call.getSite().id.equals(a.getSite().id)) {
					return 1;
				}
				if (call.getSite().id.equals(b.getSite().id)) {
					return -1;
				}
			}
			// then same agent opps
			if (!a.getAssignedTo().key.equals(b.getAssignedTo().key)) {
				if (loggedIn.key.equals(a.getAssignedTo().key)) {
					return 1;
				}
				if (loggedIn.key.equals(b.getAssignedTo().key)) {
					return -1;
				}
			}
			// then hot before cold
			int stageCompare = a.getStage().compareTo(b.getStage());
			if (stageCompare != 0) {
				return stageCompare;
			}

			return 0;
		};
		Locator.forEach(query, contact -> {
			final JsonList list = new JsonList(1);
			contacts.add(new JsonMap()
				.$("id", contact.id)
				.$("name", contact.getLastNameFirstInitial())
				.$("leads", list));
			Locator.forEach(Opportunity.withContact(contact).and(Opportunity.withSiteIdIn(relatedSites(call.getSite().id))),
				opp -> {
				if (matchQuality.compare(opp, preferred[0]) > 0) {
					preferred[0] = opp;
				}
				final ScriptNode root = opp.getProductLine().getRoot();
				list.add(new JsonMap()
					.$("id", opp.id)
					.$("source", opp.getSource())
					.$("stage", opp.getStage())
					.$("productLine", new JsonMap()
						.$("id", opp.getProductLine().id)
						.$("name", opp.getProductLine().getName())
						.$("script", root == null ? null : root.id))
					.$("agent", new JsonMap()
						.$("key", opp.getAssignedTo().key)
						.$("name", opp.getAssignedTo().getLastNameFirstInitial()))
					.$("site", new JsonMap()
						.$("id", opp.getSite().id)
						.$("name", opp.getSite().getName())));
			});
		});
		final ProductLine productLine =
			call.getQueue() == null
				? Locator.$(new ProductLine(ProductLine.unassigned))
				: call.getQueue().getProductLine();
		final Integer script = productLine == null || productLine.getRoot() == null ? null : productLine.getRoot().id;
		final Site site = call.getSite();

		final JsonMap map = new JsonMap()
			.$("contacts", contacts)
			.$("site", new JsonMap()
				.$("id", site.id)
				.$("name", site.getName()))
			.$("path", new JsonMap()
				.$("contact", preferred[0] == null ? "new" : preferred[0].getContact().id.toString())
				.$("lead", preferred[0] == null ? "new" : preferred[0].id.toString())
				.$("script", script));


		if (productLine != null) {
			map.$("productLine", new JsonMap()
				.$("id", productLine.id)
				.$("name", productLine.getName()));
		}

		final CallerId remoteCallerId = call.getRemoteCallerId();
		map.$("source", call.getSource());
		if (remoteCallerId != null) {
			map.$("phone", remoteCallerId.getNumber());
			String[] split = remoteCallerId.getName().split("[ ]", 2);
			map.$("firstName", split[0]);
			if (split.length == 2) {
				map.$("lastName", split[1]);
			}
			final AreaCodeTime areaCodeTime = AreaCodeTime.getAreaCodeTime(remoteCallerId.getNumber());
			if (areaCodeTime != null) {
				map.$("state", areaCodeTime.getUsState());
			}
		}
		return map;
	}
}


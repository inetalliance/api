package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.Comparator;
import java.util.regex.Pattern;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.core.Strings.isNotEmpty;
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
        val q = request.getParameter("q");
        val remoteCid = call.getRemoteCallerId();
        val cid =
                remoteCid == null || isEmpty(remoteCid.getNumber()) ? null : remoteCid.getNumber();
        if (isNotEmpty(q)) {
            val siteGroups = call.getSite().getSiteGroups();
            val search = new Search<>(Contact.class, getParameter(request, "n", 10),
                    q.split(" "));
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
        val preferred = new Opportunity[1];
        val contacts = new JsonList(1);
        val loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
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
            return a.getStage().compareTo(b.getStage());
        };
        Locator.forEach(query, contact -> {
            val list = new JsonList(1);
            contacts
                    .add(new JsonMap().$("id", contact.id).$("name", contact.getFullName()).$("leads", list));
            Locator.forEach(Opportunity.withContact(contact)
                            .and(Opportunity.withSiteIdIn(relatedSites(call.getSite().id))),
                    opp -> {
                        if (matchQuality.compare(opp, preferred[0]) > 0) {
                            preferred[0] = opp;
                        }
                        val root = opp.getProductLine().getRoot();
                        list.add(new JsonMap().$("id", opp.id)
                                .$("source", opp.getSource())
                                .$("stage", opp.getStage())
                                .$("productLine", new JsonMap().$("id", opp.getProductLine().id)
                                        .$("name", opp.getProductLine().getName())
                                        .$("script",
                                                root == null ? null : root.id))
                                .$("agent", new JsonMap().$("key", opp.getAssignedTo().key)
                                        .$("name", opp.getAssignedTo()
                                                .getFirstNameLastInitial()))
                                .$("site", new JsonMap().$("id", opp.getSite().id)
                                        .$("name", opp.getSite().getName())));
                    });
        });
        val productLine =
                call.getQueue() == null ? Locator.$(new ProductLine(ProductLine.unassigned)) :
                        call.getQueue().getProductLine();
        val script =
                productLine == null || productLine.getRoot() == null ? null : productLine.getRoot().id;
        val site = call.getSite();

        val map = new JsonMap().$("contacts", contacts)
                .$("site", new JsonMap().$("id", site.id).$("name", site.getName()))
                .$("path", new JsonMap().$("contact", preferred[0] == null
                                ? "new"
                                : preferred[0].getContact().id.toString())
                        .$("lead", preferred[0] == null
                                ? "new"
                                : preferred[0].id.toString())
                        .$("script", script));

        if (productLine != null) {
            map.$("productLine", new JsonMap().$("id", productLine.id).$("name", productLine.getName()));
        }

        val remoteCallerId = call.getRemoteCallerId();
        map.$("direction", call.getDirection());
        map.$("source", call.getSource());
        if (call.getSource() != null && call.getSource() == SaleSource.REFERRAL) {
            val queue = call.getQueue();
            if (queue != null) {
                val affiliate = queue.getAffiliate();
                if (affiliate == null) {
                    log.error(() -> "queue %s is referral, but has no affiliate".formatted(queue.key));

                } else {
                    map.$("referrer", affiliate.getDomain());
                }

            }
        }
        if (remoteCallerId != null) {
            map.$("phone", remoteCallerId.getNumber());
            var split = remoteCallerId.getName().split(" ", 2);
            map.$("firstName", split[0]);
            if (split.length == 2) {
                map.$("lastName", split[1]);
            }
            val areaCodeTime = AreaCodeTime.getAreaCodeTime(remoteCallerId.getNumber());
            if (areaCodeTime != null) {
                map.$("state", areaCodeTime.getUsState());
            }
        }
        return map;
    }

    private static final Log log = new Log();
}


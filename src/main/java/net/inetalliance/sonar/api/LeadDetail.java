package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.Address;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.Model;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonInteger;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;

import static com.callgrove.types.ContactType.CUSTOMER;
import static com.callgrove.types.SaleSource.MANUAL;
import static com.callgrove.types.SalesStage.HOT;
import static com.callgrove.types.SalesStage.SOLD;
import static java.lang.System.currentTimeMillis;
import static java.util.regex.Pattern.compile;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.types.geopolitical.Country.UNITED_STATES;

public class LeadDetail
		extends TypeModel<Opportunity> {

	public LeadDetail() {
		super(Opportunity.class, compile("/api/lead/(.*)"));
	}

	@Override
	protected Key<Opportunity> getKey(final Matcher m) {
		return Key.$(Opportunity.class, m.group(1));
	}

	@Override
	public void init(final ServletConfig config)
			throws ServletException {
		super.init(config);
	}

	@Override
	protected Opportunity getDefaults(final Key<Opportunity> key, final HttpServletRequest request) {
		final Opportunity opp = new Opportunity();
		opp.setStage(HOT);
		opp.setEstimatedClose(new DateMidnight());
		opp.setAssignedTo($(new Agent(Auth.getAuthorized(request).getPhone())));
		opp.setSource(MANUAL);
		final Contact contact = new Contact();
		opp.setContact(contact);
		final Address shipping = new Address();
		shipping.setCountry(UNITED_STATES);
		contact.setShipping(shipping);
		contact.setBilling(shipping);
		return opp;
	}

	@Override
	protected boolean isAuthorized(final HttpServletRequest request, final Opportunity opportunity) {
		final Authorized ticket = Auth.getAuthorized(request);
		return opportunity.isAuthorized($(new Agent(ticket.getPhone())), ticket, new DateTime());
	}

	@Override
	protected Json update(final Key<Opportunity> key, final HttpServletRequest request,
			final HttpServletResponse response, final Opportunity opportunity, final JsonMap data)
			throws IOException {
		data.remove("localTime");
		data.remove("site");
		data.remove("productLine");
		data.remove("productLines");
		final JsonMap contactData = (JsonMap) data.remove("contact");
		final Contact contact = opportunity.getContact();
		Model.update(request, contact, contactData);
		return super.update(key, request, response, opportunity, data);
	}

	@Override
	public JsonMap create(final Key<Opportunity> key, final HttpServletRequest request,
			final HttpServletResponse response, final JsonMap data) {
		final JsonMap contactData = (JsonMap) data.remove("contact");
		final JsonInteger productLine = (JsonInteger) data.remove("productLine");
		final JsonInteger site = (JsonInteger) data.remove("site");
		contactData.put("contactType", CUSTOMER);
		final JsonMap contact = Model.createObject(Key.$(Contact.class, null), request, response, contactData,
		                                           arg -> new JsonMap().$("id", arg.id));
		if (contact == null) {
			return null; // error in saving
		}
		if (contact.containsKey("id")) {
			data.put("assignedTo", Auth.getAuthorized(request).getPhone());
			data.put("created", new DateTime());
			data.put("contact", contact.getInteger("id"));
			data.put("productLine", productLine.toInteger());
			data.put("site", site.toInteger());
			return super.create(key, request, response, data);
		} else {
			return new JsonMap().$("errors", new JsonMap().$("contact", contact.get("errors")));
		}
	}

	@Override
	protected Json toJson(final Key<Opportunity> key, final Opportunity opp, final HttpServletRequest request) {
		return toJson(opp);
	}

	protected static Json toJson(final Opportunity opp) {
		final JsonMap map = new JsonMap().$("purchasingFor")
		                                 .$("notes")
		                                 .$("amount")
		                                 .$("stage")
		                                 .$("estimatedClose")
		                                 .$("reminder")
		                                 .$("invoiceNumber")
		                                 .$("poNumber")
		                                 .$("contact", new JsonMap().$("firstName")
		                                                            .$("lastName")
		                                                            .$("email")
		                                                            .$("billing", new JsonMap().$("street")
		                                                                                       .$("city")
		                                                                                       .$("state")
		                                                                                       .$("country")
		                                                                                       .$("postalCode")
		                                                                                       .$("phone"))
		                                                            .$("shipping", new JsonMap().$("street")
		                                                                                        .$("city")
		                                                                                        .$("state")
		                                                                                        .$("country")
		                                                                                        .$("postalCode")
		                                                                                        .$("phone"))
		                                                            .$("contractor", new JsonMap().$("name")
		                                                                                          .$("officePhone")
		                                                                                          .$("mobilePhone"))
		                                                            .$("installer", new JsonMap().$("name")
		                                                                                         .$("officePhone")
		                                                                                         .$("mobilePhone")))
		                                 .$("site", new JsonMap().$("name").$("uri"));

		if (opp.getStage() == SOLD) {
			map.$("saleDate");
		}

		if (opp.id == null) {
			map.$("id", "new");
		} else {
			map.$("id");
		}

		Info.$(Opportunity.class).fill(opp, map);

		if (opp.id == null) {
			final JsonList sites = new JsonList();
			forEach(Query.all(SiteGroup.class).orderBy("name", ASCENDING), group -> {
				for (final Site site : group.getSites()) {
					sites.add(new JsonMap().$("value", site.id).$("label", site.getName()).$("group", group.getName()));
				}
			});
			map.$("sites", sites);

			map.$("productLines", JsonList.collect(Locator.$$(Query.all(ProductLine.class).orderBy("name", ASCENDING)),
			                                       productLine -> new JsonMap().$("value", productLine.id)
			                                                                   .$("label", productLine.getName())));

		}

		final Contact contact = opp.getContact();
		final String phone = contact.getPhone();
		if (StringFun.isNotEmpty(phone)) {
			final AreaCodeTime time = AreaCodeTime.getAreaCodeTime(phone);
			map.put("localTime", time == null ? null : time.getDateTimeZone().getOffset(currentTimeMillis()));
		}

		if (opp.id != null) {
			final Site site = opp.getSite();
			final ProductLine productLine = opp.getProductLine();
			final Map<ProductLine, String> webpages = site.getWebpages();
			final String webpage = webpages.get(productLine);

			map.$("productLine", new JsonMap().$("name", productLine.getName()).$("uri", webpage));

		}

		return map;
	}
}




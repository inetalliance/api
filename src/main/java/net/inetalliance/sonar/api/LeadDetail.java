package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Strings;
import com.callgrove.obj.*;
import com.callgrove.types.Address;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.Model;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonInteger;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;

import static com.callgrove.types.ContactType.CUSTOMER;
import static com.callgrove.types.SaleSource.MANUAL;
import static com.callgrove.types.SalesStage.HOT;
import static com.callgrove.types.SalesStage.SOLD;
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

    protected static Json toJson(final Opportunity opp) {
        val map = new JsonMap().$("purchasingFor")
                .$("notes")
                .$("trustPilotProspect")
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
            val sites = new JsonList();
            forEach(Query.all(SiteGroup.class).orderBy("name", ASCENDING), group -> {
                for (val site : group.getSites()) {
                    sites.add(new JsonMap().$("value", site.id).$("label", site.getName())
                            .$("group", group.getName()));
                }
            });
            map.$("sites", sites);

            map.$("productLines",
                    JsonList.collect(Locator.$$(Query.all(ProductLine.class).orderBy("name", ASCENDING)),
                            productLine -> new JsonMap().$("value", productLine.id)
                                    .$("label", productLine.getName())));

        }

        val contact = opp.getContact();
        val phone = contact.getPhone();
        if (Strings.isNotEmpty(phone)) {
            val time = AreaCodeTime.getAreaCodeTime(phone);
            map.put("localTime",
                    time == null ? null : Instant.now().atZone(time.getLocalDateTimeZone()).getOffset().getTotalSeconds());
        }

        if (opp.id != null) {
            val site = opp.getSite();
            val productLine = opp.getProductLine();
            val webpages = site.getWebpages();
            val webpage = webpages.get(productLine);

            map.$("productLine", new JsonMap().$("name", productLine.getName()).$("uri", webpage));

        }

        return map;
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
        val opp = new Opportunity();
        opp.setStage(HOT);
        opp.setEstimatedClose(LocalDate.now());
        opp.setAssignedTo($(new Agent(Auth.getAuthorized(request).getPhone())));
        opp.setSource(MANUAL);
        val contact = new Contact();
        opp.setContact(contact);
        val shipping = new Address();
        shipping.setCountry(UNITED_STATES);
        contact.setShipping(shipping);
        contact.setBilling(shipping);
        return opp;
    }

    @Override
    protected boolean isAuthorized(final HttpServletRequest request, final Opportunity opportunity) {
        val ticket = Auth.getAuthorized(request);
        return opportunity.isAuthorized($(new Agent(ticket.getPhone())), ticket, LocalDateTime.now());
    }

    @Override
    protected Json update(final Key<Opportunity> key, final HttpServletRequest request,
                          final HttpServletResponse response, final Opportunity opportunity, final JsonMap data) {
        data.remove("localTime");
        data.remove("site");
        data.remove("productLine");
        data.remove("productLines");
        val contactData = (JsonMap) data.remove("contact");
        val contact = opportunity.getContact();
        Model.update(request, contact, contactData);
        return super.update(key, request, response, opportunity, data);
    }

    @Override
    public JsonMap create(final Key<Opportunity> key, final HttpServletRequest request,
                          final HttpServletResponse response, final JsonMap data) {
        val contactData = (JsonMap) data.remove("contact");
        val productLine = (JsonInteger) data.remove("productLine");
        val site = (JsonInteger) data.remove("site");
        contactData.put("contactType", CUSTOMER);
        val contact = Model
                .createObject(Key.$(Contact.class, null), request, response, contactData,
                        arg -> new JsonMap().$("id", arg.id));
        if (contact == null) {
            return null; // error in saving
        }
        if (contact.containsKey("id")) {
            data.put("assignedTo", Auth.getAuthorized(request).getPhone());
            data.put("created", LocalDateTime.now());
            data.put("contact", contact.getInteger("id"));
            data.put("productLine", productLine.toInteger());
            data.put("site", site.toInteger());
            return super.create(key, request, response, data);
        } else {
            return new JsonMap().$("errors", new JsonMap().$("contact", contact.get("errors")));
        }
    }

    @Override
    protected Json toJson(final Key<Opportunity> key, final Opportunity opp,
                          final HttpServletRequest request) {
        return toJson(opp);
    }
}




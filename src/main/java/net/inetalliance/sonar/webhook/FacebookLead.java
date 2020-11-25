package net.inetalliance.sonar.webhook;

import com.callgrove.obj.*;
import com.callgrove.obj.Site.SiteQueue;
import com.callgrove.types.Address;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import com.slack.api.Slack;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.RoundRobinSelector;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.callgrove.obj.ProductLine.withNameLike;
import static com.callgrove.obj.Site.withAbbreviation;
import static com.callgrove.types.Tier.NEVER;
import static java.lang.Character.isDigit;
import static java.lang.String.format;
import static java.util.Collections.shuffle;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.of;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.types.json.Json.dateTimeFormat2;


@WebServlet("/hook/facebookLead")
public class FacebookLead
        extends AngularServlet {

    private static final transient Log log = Log.getInstance(FacebookLead.class);
    public static final String AMG_TOKEN = "SLACK_API_TOKEN_REDACTED";
    private final Slack slack = Slack.getInstance();

    private final Map<Integer,SkillRoute> routes = new HashMap<>();
    private final Map<Integer, RoundRobinSelector> selectors = new LazyMap<>(
            new HashMap<>(), id -> RoundRobinSelector.$(routes.get(id)));
    private static final RoundRobinSelector selector = RoundRobinSelector.$($(new SkillRoute(10128)));


    private static String extractPhone(final String value) {
        if (isEmpty(value)) {
            return null;
        }
        var s = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isDigit(c) && (c != '1' || i > 0)) {
                s.append(c);
            }
        }
        return s.toString();

    }

    public Pattern getPattern() {
        return Pattern.compile("/hook/facebookLead");
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        response.getWriter().println("Use POST, dummy.");
    }

    public Agent getAgent(final int emailQueueId) {
        final var emailQueue = $(new EmailQueue(emailQueueId));
        if (emailQueue == null) {
            throw new NullPointerException();
        }
        final var queue = $(emailQueue.getQueue());
        if (queue == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" does not have a call queue associated with it!",
                            emailQueue.getName()));
        }
        final var skillRoute = $(queue.getSkillRoute());
        if (skillRoute == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" has a call queue (%s), but no skill route!",
                            emailQueue.getName(),
                            queue.getName()));
        }
        final var siteQueue = $1(SiteQueue.withQueue(queue));
        if (siteQueue != null) {
            final var site = siteQueue.site;
            if (site.isDistributor()) {
                return $(new Agent("7006")); // mat
            } else {
                final var members = skillRoute.getConfiguredMembers();
                members.remove(NEVER);
                for (final var tier : complementOf(of(NEVER))) {
                    if (!members.containsKey(tier)) {
                        continue;
                    }
                    final var agents = new ArrayList<>(members.get(tier));
                    shuffle(agents);
                    for (final var agent : agents) {
                        if (!agent.isPaused() && !agent.isForwarded()) {
                            return agent;
                        }
                    }
                }
                if (members.isEmpty()) {
                    log.error("No agents configured for %s", queue.key);
                    throw new RuntimeException();
                } else {
                    log.warning("No agent logged in to %s, defaulting to random agents by priority",
                            queue.key);
                    for (final var tier : complementOf(of(NEVER))) {
                        if (!members.containsKey(tier)) {
                            continue;
                        }
                        final var agents = new ArrayList<>(members.get(tier));
                        shuffle(agents);
                        return agents.iterator().next();
                    }
                }
                log.error("This really shouldn't ever happen. Facepalm.", queue.key);
            }
            throw new IllegalStateException();
        }
        return null;
    }


    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) {
        try {
            log.info("Processing webhook from Zapier");
            final var json = JsonMap.parse(request.getInputStream());

            final var fullName = json.get("fullName");
            final Contact contact;
            final var email = json.get("email");
            final var zip = json.get("zip");
            var phone = extractPhone(json.get("phone"));
            var existingContact = $1(Contact.withEmail(email));

            if (existingContact == null) {
                contact = new Contact();
                var split = fullName.split("[ ]", 2);
                contact.setFirstName(split[0]);
                contact.setLastName(split.length == 2 ? split[1] : "");
                contact.setContactType(ContactType.CUSTOMER);
                final Address address = new Address();
                address.setPhone(phone);
                address.setPostalCode(zip);
                var areaCode = AreaCodeTime.getAreaCodeTime(phone);
                if (areaCode != null) {
                    address.setState(areaCode.getUsState());
                }
                contact.setBilling(address);
                contact.setShipping(address);
                contact.setEmail(email);
                create("FacebookLead", contact);
            } else {
                if (isEmpty(existingContact.getShipping().getPhone())) {
                    update(existingContact, "FacebookLead", copy -> {
                        copy.getShipping().setPhone(phone);
                        var areaCode = AreaCodeTime.getAreaCodeTime(phone);
                        if (areaCode != null) {
                            copy.getShipping().setState(areaCode.getUsState());
                        }
                        if (StringFun.isNotEmpty(zip)) {
                            copy.getShipping().setPostalCode(zip);
                        }
                    });
                }
                contact = existingContact;
            }

            final var site = $1(withAbbreviation(json.get("site")));
            final var amount = new Currency(json.getDouble("amount"));
            final var fullDate = json.get("date");
            final var date = dateTimeFormat2.parseDateTime(fullDate.split("[+]", 2)[0]);
            final var productLine = $1(withNameLike(json.get("productLine")));

            if (productLine == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println("Unknown product line: " + json.get("productLine"));
                return;
            }

            final Opportunity opp;
            var existingOpp = $1(Opportunity.withContact(contact).and(Opportunity.withProductLine(productLine)));
            final Agent agent;
            var reassigned = false;
            if (existingOpp == null) {
                opp = new Opportunity();
                agent = getAgent(productLine.id);
                opp.setAssignedTo(agent);
                if ("form".equals(json.get("source"))) {
                    opp.setSource(SaleSource.SURVEY);
                } else {
                    opp.setSource(SaleSource.SOCIAL);
                }
                opp.setAmount(amount);
                opp.setStage(SalesStage.HOT);
                opp.setContact(contact);
                opp.setProductLine(productLine);
                opp.setPurchasingFor(Relation.SELF);
                opp.setSite(site);
                opp.setCreated(date);
                opp.setReminder(date);
                opp.setEstimatedClose(new DateMidnight());
                create("FacebookLead", opp);
            } else {
                Locator.update(existingOpp, "FacebookLead", copy -> {
                    copy.setReminder(date);
                });
                opp = existingOpp;
                if (opp.getAssignedTo().isLocked()) {
                    agent = getAgent(productLine.id);
                    reassigned = true;
                    Locator.update(existingOpp, "FacebookLead", copy -> {
                        copy.setAssignedTo(agent);
                    });
                } else {
                    agent = opp.getAssignedTo();
                }
            }
            final var link = String.format("https://crm.inetalliance.net/#/lead/%d", opp.id);
            final var msg = format("%s %s Lead *%s* %s => %s",
                    reassigned ? "Reassigned " : (existingOpp == null ? "New" : "Updated"),
                    opp.getSource() == SaleSource.SOCIAL ? "Facebook" : "Form",
                    contact.getFullName(), link, agent.getFirstNameLastInitial());

            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("@" + agent.getSlackName())
                    .token(System.getenv("SLACK_API_TOKEN"))
                    .text(msg).build());
            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("#digital-leads")
                    .token(System.getenv("SLACK_API_TOKEN"))
                    .text(msg).build());

            respond(response,
                    new JsonMap().$("contact", contact.getId()).$("agent", agent.getSlackName())
                            .$("opp", opp.getId()));
            log.info(msg);
        } catch (Throwable e) {
            log.error(e);
        }
    }

    private boolean isAfterHours() {
        var now = new DateTime();
        switch (now.getDayOfWeek()) {
            case DateTimeConstants.SATURDAY:
            case DateTimeConstants.SUNDAY:
                return true;
            default:
                var h = now.getHourOfDay();
                return h <= 7 || h >= 20;
        }
    }

    private Agent getAgent(final Integer product) {
        return $(new Agent((isAfterHours() ? selector : selectors.get(product)).select()));
    }

    @Override
    public void init() {
        var ag = Locator.$(new Site(42));
        ag.getQueues().forEach(q-> routes.put(q.getProductLine().id,q.getSkillRoute()));
        routes.forEach((p,s)-> {
            log.info("DigiLead %s -> %s", Locator.$(new ProductLine(p)).getName(), s.getName());
        });
    }

    public static void main(String[] args) {
        var q = Query.eq(Contact.class, "state", null)
                .and(Query.eq(Contact.class, "shipping_phone", null).negate());
        var total = count(q);
        var meter = new ProgressMeter(total);
        var updated = new AtomicInteger(0);
        Locator.forEach(q, c -> {
            var areaCode = AreaCodeTime.getAreaCodeTime(extractPhone(c.getShipping().getPhone()));
            if (areaCode != null) {
                Locator.update(c, "FacebookLead", copy -> {
                    var shipping = copy.getShipping();
                    shipping.setState(areaCode.getUsState());
                    shipping.setCountry(areaCode.getCountry());
                });
            }
            meter.increment("%d/%d", updated.incrementAndGet(), total);
        });
    }
}

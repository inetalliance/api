package net.inetalliance.sonar.webhook;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.obj.Site.SiteQueue;
import com.callgrove.types.Address;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import com.slack.api.Slack;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.RoundRobinSelector;
import net.inetalliance.types.Currency;
import net.inetalliance.types.geopolitical.canada.Province;
import net.inetalliance.types.geopolitical.us.State;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import net.inetalliance.util.ProgressMeter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.obj.ProductLine.withNameLike;
import static com.callgrove.obj.Site.withAbbreviation;
import static com.callgrove.types.Tier.NEVER;
import static java.lang.Character.isDigit;
import static java.lang.String.format;
import static java.util.Collections.shuffle;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.of;
import static net.inetalliance.potion.Locator.$1;


@WebServlet("/hook/facebookLead")
public class FacebookLead
        extends AngularServlet {

    private static final Log log = new Log();
    public static final String AMG_TOKEN = "SLACK_API_TOKEN_REDACTED";
    private final Slack slack = Slack.getInstance();

    private final Map<Integer, SkillRoute> routes = new HashMap<>();
    private final Map<Integer, RoundRobinSelector> selectors = new LazyMap<>(
            new HashMap<>(), id -> RoundRobinSelector.$(routes.get(id)));
    private static final RoundRobinSelector selector = RoundRobinSelector.$(Locator.$(new SkillRoute(10128)));


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
        val emailQueue = Locator.$(new EmailQueue(emailQueueId));
        if (emailQueue == null) {
            throw new NullPointerException();
        }
        val queue = Locator.$(emailQueue.getQueue());
        if (queue == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" does not have a call queue associated with it!",
                            emailQueue.getName()));
        }
        val skillRoute = Locator.$(queue.getSkillRoute());
        if (skillRoute == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" has a call queue (%s), but no skill route!",
                            emailQueue.getName(),
                            queue.getName()));
        }
        val siteQueue = $1(SiteQueue.withQueue(queue));
        if (siteQueue != null) {
            val site = siteQueue.site;
            if (site.isDistributor()) {
                return Locator.$(new Agent("7006")); // mat
            } else {
                val members = skillRoute.getConfiguredMembers();
                members.remove(NEVER);
                for (val tier : complementOf(of(NEVER))) {
                    if (!members.containsKey(tier)) {
                        continue;
                    }
                    val agents = new ArrayList<>(members.get(tier));
                    shuffle(agents);
                    for (val agent : agents) {
                        if (!agent.isPaused() && !agent.isForwarded()) {
                            return agent;
                        }
                    }
                }
                if (members.isEmpty()) {
                    log.error("No agents configured for %s", queue.key);
                    throw new RuntimeException();
                } else {
                    log.warn(() -> "No agent logged in to %s, defaulting to random agents by priority".formatted(
                            queue.key));
                    for (val tier : complementOf(of(NEVER))) {
                        if (!members.containsKey(tier)) {
                            continue;
                        }
                        val agents = new ArrayList<>(members.get(tier));
                        shuffle(agents);
                        return agents.getFirst();
                    }
                }
                log.error(()->"This really shouldn't ever happen. queue: %s".formatted(queue.key));
            }
            throw new IllegalStateException();
        }
        return null;
    }


    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) {
        try {
            log.info(()->"Processing webhook from Zapier");
            val json = JsonMap.parse(request.getInputStream());

            val fullName = json.get("fullName");
            final Contact contact;
            val email = json.get("email");
            val zip = json.get("zip");
            var phone = extractPhone(json.get("phone"));
            var existingContact = $1(Contact.withEmail(email));
            var provinceAbbreviation = json.get("province");
            var company = json.get("company");
            var state = json.get("state");
            var city = json.get("city");


            if (existingContact == null) {
                contact = new Contact();
                var split = fullName.split(" ", 2);
                contact.setFirstName(split[0]);
                contact.setLastName(split.length == 2 ? split[1] : "");
                contact.setCompany(company);
                contact.setContactType(ContactType.CUSTOMER);
                final Address address = new Address();
                address.setPhone(phone);
                address.setPostalCode(zip);
                if (isNotEmpty(provinceAbbreviation)) {
                    address.setCanadaDivision(Province.fromAbbreviation(provinceAbbreviation));
                }
                if (state != null) {
                    address.setState(State.fromAbbreviation(state));
                }
                address.setCity(city);
                var areaCode = AreaCodeTime.getAreaCodeTime(phone);
                if (address.getState() == null && address.getCanadaDivision() == null && areaCode != null) {
                    switch (areaCode.getCountry()) {
                        case UNITED_STATES:
                            address.setState(areaCode.getUsState());
                            break;
                        case CANADA:
                            address.setCanadaDivision(areaCode.getCaDivision());
                            break;
                    }
                }
                contact.setBilling(address);
                contact.setShipping(address);
                contact.setEmail(email);
                Locator.create("FacebookLead", contact);
            } else {
                if (isEmpty(existingContact.getShipping().getPhone())) {
                    Locator.update(existingContact, "FacebookLead", copy -> {
                        var address = copy.getShipping();
                        address.setPhone(phone);
                        if (state != null) {
                            address.setState(State.fromAbbreviation(state));
                        }
                        address.setCity(city);
                        var areaCode = AreaCodeTime.getAreaCodeTime(phone);
                        if (address.getState() == null && address.getCanadaDivision() == null && areaCode != null) {
                            switch (areaCode.getCountry()) {
                                case UNITED_STATES -> copy.getShipping().setState(areaCode.getUsState());
                                case CANADA -> {
                                    if (copy.getShipping().getCanadaDivision() == null) {
                                        copy.getShipping().setCanadaDivision(areaCode.getCaDivision());
                                    }
                                }
                            }
                        }
                        copy.setCompany(company);
                        if (isNotEmpty(provinceAbbreviation)) {
                            copy.getShipping().setCanadaDivision(Province.fromAbbreviation(provinceAbbreviation));
                        }
                        if (isNotEmpty(zip)) {
                            copy.getShipping().setPostalCode(zip);
                        }
                    });
                }
                contact = existingContact;
            }

            val site = $1(withAbbreviation(json.get("site")));
            val amount = new Currency(json.getDouble("amount"));
            val fullDate = json.get("date");
            val date = Json.parseDate(fullDate.split("[+]", 2)[0]);
            val productLine = $1(withNameLike(json.get("productLine")));

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
                opp.setEstimatedClose(LocalDate.now());
                Locator.create("FacebookLead", opp);
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
            val link = String.format("https://crm.inetalliance.net/#/lead/%d", opp.id);
            val msg = format("%s %s Lead *%s* %s => %s",
                    reassigned ? "Reassigned " : (existingOpp == null ? "New" : "Updated"),
                    opp.getSource() == SaleSource.SOCIAL ? "Facebook" : "Form",
                    contact.getFullName(), link, agent.getFirstNameLastInitial());

            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("@" + agent.getSlackName())
                    .token(AMG_TOKEN)
                    .text(msg).build());
            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("#digital-leads")
                    .token(AMG_TOKEN)
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
        var now = LocalDateTime.now();
        return switch (now.getDayOfWeek()) {
            case DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> true;
            default -> {
                var h = now.getHour();
                yield h <= 7 || h >= 20;
            }
        };
    }

    private Agent getAgent(final Integer product) {
        return Locator.$(new Agent((isAfterHours() ? selector : selectors.get(product)).select()));
    }

    @Override
    public void init() {
        var ag = Locator.$(new Site(42));
        var digi = Locator.$(new SkillRoute(10128));
        ag.getQueues().forEach(q -> routes.put(q.getProductLine().id, digi));
        routes.forEach((p, s) -> log.info("DigiLead %s -> %s", Locator.$(new ProductLine(p)).getName(), s.getName()));
    }

    public static void main(String[] args) {
        var q = Query.eq(Contact.class, "state", null)
                .and(Query.eq(Contact.class, "shipping_phone", null).negate());
        var total = Locator.count(q);
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

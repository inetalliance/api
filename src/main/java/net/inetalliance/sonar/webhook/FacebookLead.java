package net.inetalliance.sonar.webhook;

import com.callgrove.obj.*;
import com.callgrove.obj.Site.SiteQueue;
import com.callgrove.types.*;
import com.slack.api.Slack;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.RoundRobinSelector;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.callgrove.types.Tier.NEVER;
import static java.lang.String.format;
import static java.util.Collections.shuffle;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.of;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.create;


@WebServlet("/hook/facebookLead")
public class FacebookLead
        extends AngularServlet {

    private static final transient Log log = Log.getInstance(FacebookLead.class);
    private static final Pattern phonePattern = Pattern.compile(".*(\\d{10})");
    public static final String AMG_TOKEN = "SLACK_API_TOKEN_REDACTED";
    private final Slack slack = Slack.getInstance();
    private static final RoundRobinSelector selector = RoundRobinSelector.$(Locator.$(new SkillRoute(10128)));

    private static String extractPhone(final String value) {
        if (StringFun.isEmpty(value)) {
            return null;
        }
        var s = new StringBuilder(value.length());
        for(int i=0; i<value.length(); i++) {
           char c = value.charAt(i);
           if(Character.isDigit(c)) {
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
        final EmailQueue emailQueue = Locator.$(new EmailQueue(emailQueueId));
        if (emailQueue == null) {
            throw new NullPointerException();
        }
        final Queue queue = Locator.$(emailQueue.getQueue());
        if (queue == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" does not have a call queue associated with it!",
                            emailQueue.getName()));
        }
        final SkillRoute skillRoute = Locator.$(queue.getSkillRoute());
        if (skillRoute == null) {
            throw new NullPointerException(
                    format("Email queue \"%s\" has a call queue (%s), but no skill route!",
                            emailQueue.getName(),
                            queue.getName()));
        }
        final SiteQueue siteQueue = $1(SiteQueue.withQueue(queue));
        if (siteQueue != null) {
            final Site site = siteQueue.site;
            if (site.isDistributor()) {
                return Locator.$(new Agent("7006")); // mat
            } else {
                final Map<Tier, Collection<Agent>> members = skillRoute.getConfiguredMembers();
                members.remove(NEVER);
                for (final Tier tier : complementOf(of(NEVER))) {
                    if (!members.containsKey(tier)) {
                        continue;
                    }
                    final List<Agent> agents = new ArrayList<>(members.get(tier));
                    shuffle(agents);
                    for (final Agent agent : agents) {
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
                    for (final Tier tier : complementOf(of(NEVER))) {
                        if (!members.containsKey(tier)) {
                            continue;
                        }
                        final List<Agent> agents = new ArrayList<>(members.get(tier));
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
            final JsonMap json = JsonMap.parse(request.getInputStream());

            final String fullName = json.get("fullName");
            final Contact contact = new Contact();
            String[] split = fullName.split("[ ]", 2);
            contact.setFirstName(split[0]);
            contact.setLastName(split.length == 2 ? split[1] : "");
            contact.setContactType(ContactType.CUSTOMER);
            final Address address = new Address();
            address.setPhone(extractPhone(json.get("phone")));
            contact.setBilling(address);
            contact.setShipping(address);
            contact.setEmail(json.get("email"));
            create("FacebookLead", contact);

            final Site site = $1(Site.withAbbreviation(json.get("site")));
            final Currency amount = new Currency(json.getDouble("amount"));
            final String fullDate = json.get("date");
            final DateTime date = Json.dateTimeFormat2.parseDateTime(fullDate.split("[+]", 2)[0]);
            final ProductLine productLine = $1(ProductLine.withNameLike(json.get("productLine")));

            if (productLine == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println("Unknown product line: " + json.get("productLine"));
                return;
            }

            final Opportunity opp = new Opportunity();
            var agent = getAgent();
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
            final var link = String.format("https://crm.inetalliance.net/#/lead/%d", opp.id);
            final var msg = format("New %s Lead *%s* %s => %s", opp.getSource() == SaleSource.SOCIAL ? "Facebook" : "Form",
                    contact.getFullName(), link, agent.getFirstNameLastInitial());

            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("@" + agent.getSlackName())
                    .token(System.getenv("SLACK_API_TOKEN"))
                    .text(msg).build());
            slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                    .channel("#digital-leads")
                    .token(System.getenv("SLACK_API_TOKEN"))
                    .text(msg).build());

            final JsonMap result =
                    new JsonMap().$("contact", contact.getId()).$("agent", agent.getSlackName())
                            .$("opp", opp.getId());
            respond(response, result);
            log.info("Created opp %d via Zapier/Form assigned to %s", opp.getId(), agent.getFullName());
        } catch (Throwable e) {
            log.error(e);
        }
    }

    private Agent getAgent() {
        return Locator.$(new Agent(selector.select()));
    }
}

package net.inetalliance.sonar.webhook;

import com.callgrove.jobs.Hud;
import com.callgrove.obj.*;
import com.callgrove.types.Address;
import com.callgrove.types.Tier;
import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.daemonic.RuntimeKeeper;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.callgrove.obj.Webhook.withApiKey;
import static com.callgrove.types.ContactType.CUSTOMER;
import static com.callgrove.types.SaleSource.THIRD_PARTY_ONLINE;
import static com.callgrove.types.SalesStage.HOT;
import static java.lang.String.format;
import static java.util.Collections.sort;
import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.potion.obj.AddressPo.unformatPhoneNumber;

@WebServlet("/hook/future")
public class FutureLead
        extends AngularServlet {
    private List<Slot> queue;

    public static final String AMG_TOKEN = "SLACK_API_TOKEN_REDACTED";
    private Slack slack = Slack.getInstance();
    private static AtomicInteger reqId = new AtomicInteger(0);

    public Pattern getPattern() {
        return Pattern.compile("/api/future");
    }

    private static final Log log = Log.getInstance(FutureLead.class);
    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) {
        var reqId = FutureLead.reqId.getAndIncrement();
        log.info("[%d] Future Lead: %s?%s", reqId, request.getRequestURI(),request.getQueryString());
        final Webhook webhook =
                $1(withApiKey("c3387588-fbf4-4372-9e31-0cbc001418b0")); // magic key for future
        try {
            var data = JsonMap.parse(request.getInputStream());
            log.info("[%d] Future POST data: %s ", reqId, Json.pretty(data));
            if(data == null) {
                throw new BadRequestException("no JSON data specified");
            }
            var apiKey = request.getParameter("apiKey");
            if(StringFun.isEmpty(apiKey)) {
                if(!data.containsKey("apiKey")) {
                    throw new BadRequestException("must provide apiKey");
                }
                apiKey = data.get("apiKey");
            }
            if(!webhook.apiKey.equals(apiKey)) {
                throw new ForbiddenException("incorrect apiKey");
            }
            if (data.containsKey("id")) {
                var id = data.get("id");
                if ($1(Opportunity.withWebhook(webhook, id)) != null) {
                    throw new BadRequestException("Already created opportunity for id %s", id);
                }
                final Contact c = new Contact();
                c.setFirstName(data.get("firstName"));
                c.setLastName(data.get("surname"));
                final Address shipping = new Address();
                var digits = unformatPhoneNumber(data.get("phone"));
                if(digits.length()>10 && digits.startsWith("1")) {
                    digits = digits.substring(1);
                }
                shipping.setPhone(digits);
                c.setEmail(data.get("email"));
                c.setContactType(CUSTOMER);
                if (isNotEmpty(shipping.getPhone())) {
                    AreaCodeTime areaCode = AreaCodeTime.getAreaCodeTime(shipping.getPhone());
                    if (areaCode != null) {
                        shipping.setState(areaCode.getUsState());
                    }
                }
                shipping.setPostalCode(data.get("postcode"));
                c.setShipping(shipping);
                create(webhook.getName(), c);
                final Opportunity o = new Opportunity();
                o.setContact(c);
                o.setSite($(new Site(42))); // AmeriGlide
                o.setProductLine($(new ProductLine(6))); // Stair Lifts
                o.setAmount(o.getProductLine().getAverage());
                o.setEstimatedClose(new DateMidnight());
                o.setStage(HOT);
                o.setSource(THIRD_PARTY_ONLINE);
                o.setWebhook(webhook);
                o.setWorked(false);
                o.setWebhookKey(id);
                var  agent = getAgent();
                o.setAssignedTo(agent);
                o.setCreated(new DateTime());
                o.setNotes(Stream.of("site", "url", "ca1", "ca2", "ca3", "ca4", "ca5")
                        .filter(data::containsKey)
                        .map(s -> toDesc(s) + " " + data.get(s))
                        .collect(Collectors.joining("\n")));
                create(webhook.getName(), o);
                final JsonMap json = new JsonMap();
                json.put("contact", c.id);
                json.put("opportunity", o.id);
                respond(response, json);
                final var link = String.format("https://crm.inetalliance.net/#/lead/%d", o.id);
                final var msg = format("New Future Publishing Lead *%s* %s", c.getFullName(), link);

                if(!RuntimeKeeper.isDevelopment()) {
                    slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                            .channel("@" + agent.getSlackName())
                            .token(System.getenv("SLACK_API_TOKEN"))
                            .text(msg).build());
                }
                slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                        .channel("#future-leads")
                        .token(System.getenv("SLACK_API_TOKEN"))
                        .text(msg).build());
            } else {
                throw new BadRequestException("You must specify a string value for the id key");
            }
        } catch (IOException | SlackApiException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private String toDesc(String key) {
        switch(key) {
            case "ca1":
                return "Who is this Stairlift for?";
            case "ca2":
                return "What type of staircase do you have?";
            case "ca3":
                return "Are you a homeowner or authorized to make property changes?";
            case "ca4":
                return "Are you interested in other senior safety products?";
            case "ca5":
                return "When do you anticipate making a decision?";
            default:
                return key;
        }
    }

    @Override
    public void init() throws ServletException {
        super.init();
        var stairlifts = Locator.$(new SkillRoute(11));
        this.queue = new ArrayList<>();
        stairlifts.getConfiguredMembers()
                .forEach((key, value) -> {
                    if (key != Tier.NEVER) {
                        value.forEach(a -> queue.add(new Slot(a, key)));
                    }
                });
    }

    static class Slot implements Comparable<Slot> {
        Slot(Agent agent, Tier tier) {
            this.agent = agent.key;
            this.tier = tier;
        }

        @Override
        public String toString() {
            return String.format("%s: %s [%d]", tier, agent, lastSelection.getMillis());
        }

        String agent;
        Tier tier;
        DateTime lastSelection = DateTime.now().minusYears(10);

        @Override
        public int compareTo(@NotNull Slot slot) {
            if (lastSelection.plusMinutes(5).isAfterNow() || slot.lastSelection.plusMinutes(5).isAfterNow()) {
                return lastSelection.compareTo(slot.lastSelection);
            }
            var c = this.tier.compareTo(slot.tier);
            if (c == 0) {
                c = lastSelection.compareTo(slot.lastSelection);
                if (c == 0) {
                    return agent.compareTo(slot.agent);
                }
                return c;
            }
            return c;
        }
    }

    private static String select(List<Slot> queue) {
        return select(queue,0);
    }
    private static String select(List<Slot> queue, int retries) {
        sort(queue);
        var slot = queue.get(0);
        slot.lastSelection = new DateTime();
        if(retries >= queue.size() || Hud.available(slot.agent)) {
            return slot.agent;
        }
        return select(queue, retries+1);
    }

    private Agent getAgent() {
        return Locator.$(new Agent(select(queue)));
    }


}

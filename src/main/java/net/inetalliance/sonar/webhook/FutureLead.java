package net.inetalliance.sonar.webhook;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.types.Address;
import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.RoundRobinSelector;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.obj.Webhook.withApiKey;
import static com.callgrove.types.ContactType.CUSTOMER;
import static com.callgrove.types.SaleSource.THIRD_PARTY_ONLINE;
import static com.callgrove.types.SalesStage.HOT;
import static java.lang.String.format;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.potion.obj.AddressPo.unformatPhoneNumber;

@WebServlet("/hook/future")
public class FutureLead
        extends AngularServlet {

    public static final String AMG_TOKEN = "SLACK_API_TOKEN_REDACTED";
    private final Slack slack = Slack.getInstance();
    private static final AtomicInteger reqId = new AtomicInteger(0);
    private RoundRobinSelector selector;

    public Pattern getPattern() {
        return Pattern.compile("/api/future");
    }

    private static final Log log = new Log();

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) {
        var reqId = FutureLead.reqId.getAndIncrement();
        log.info("[%d] Future Lead: %s?%s", reqId, request.getRequestURI(), request.getQueryString());
        final Webhook webhook =
                $1(withApiKey("c3387588-fbf4-4372-9e31-0cbc001418b0")); // magic key for the future
        try {
            var data = JsonMap.parse(request.getInputStream());
            log.info("[%d] Future POST data: %s ", reqId, Json.pretty(data));
            var apiKey = request.getParameter("apiKey");
            if (isEmpty(apiKey)) {
                if (!data.containsKey("apiKey")) {
                    throw new BadRequestException("must provide apiKey");
                }
                apiKey = data.get("apiKey");
            }
            if (!webhook.apiKey.equals(apiKey)) {
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
                if (digits.length() > 10 && digits.startsWith("1")) {
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
                o.setEstimatedClose(LocalDate.now().plusDays(1));
                o.setStage(HOT);
                o.setSource(THIRD_PARTY_ONLINE);
                o.setWebhook(webhook);
                o.setWorked(false);
                o.setWebhookKey(id);
                var agent = getAgent();
                o.setAssignedTo(agent);
                o.setCreated(LocalDateTime.now());
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
                final var msg = format("New Future Publishing Lead *%s* %s => %s", c.getFullName(), link,
                        agent.getFirstNameLastInitial());

                if (!Cli.isDevelopment()) {
                    slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                            .channel("@" + agent.getSlackName())
                            .token(AMG_TOKEN)
                            .text(msg).build());
                }
                slack.methods().chatPostMessage(ChatPostMessageRequest.builder()
                        .channel("#digital-leads")
                        .token(AMG_TOKEN)
                        .text(msg).build());
            } else {
                throw new BadRequestException("You must specify a string value for the id key");
            }
        } catch (IOException | SlackApiException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private String toDesc(String key) {
        return switch (key) {
            case "ca1" -> "Who is this Stairlift for?";
            case "ca2" -> "What type of staircase do you have?";
            case "ca3" -> "Are you a homeowner or authorized to make property changes?";
            case "ca4" -> "Are you interested in other senior safety products?";
            case "ca5" -> "When do you anticipate making a decision?";
            default -> key;
        };
    }

    @Override
    public void init() throws ServletException {
        super.init();
        this.selector = RoundRobinSelector.$(Locator.$(new SkillRoute(10128)));
    }

    private Agent getAgent() {
        return Locator.$(new Agent(selector.select()));
    }


}

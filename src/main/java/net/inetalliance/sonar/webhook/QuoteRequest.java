package net.inetalliance.sonar.webhook;

import com.callgrove.obj.*;
import com.callgrove.types.Address;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.obj.Webhook.withApiKey;
import static com.callgrove.types.ContactType.CUSTOMER;
import static com.callgrove.types.SaleSource.THIRD_PARTY_ONLINE;
import static com.callgrove.types.SalesStage.HOT;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.potion.obj.AddressPo.unformatPhoneNumber;
import static net.inetalliance.types.geopolitical.us.State.fromAbbreviation;

@WebServlet("/hook/opportunity")
public class QuoteRequest
        extends AngularServlet {

    public Pattern getPattern() {
        return Pattern.compile("/hook/quoteRequest");
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {

        val webhook =
                $1(withApiKey("53d3befd1d210670d99efc615091ad2f")); // magic key for quote request

        var params =
                request.getParameterMap().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue()[0]));

        if ($1(Opportunity.withWebhook(webhook, params.get("leadKey"))) != null) {
            throw new BadRequestException("Already created opportunity for lead key %s",
                    params.get("leadKey"));
        }

        val c = new Contact();
        val name = params.get("name");
        var split = name.split(" ", 2);
        c.setFirstName(split[0]);
        c.setLastName(split.length == 2 ? split[1] : "Unknown");
        val shipping = new Address();
        shipping.setPhone(unformatPhoneNumber(params.get("primaryNumber")));
        c.setEmail(params.get("email"));
        c.setContactType(CUSTOMER);
        if (isNotEmpty(shipping.getPhone())) {
            var areaCode = AreaCodeTime.getAreaCodeTime(shipping.getPhone());
            if (areaCode != null) {
                shipping.setState(areaCode.getUsState());
            }
        }
        shipping.setState(fromAbbreviation(params.get("state")));
        c.setShipping(shipping);
        create(webhook.getName(), c);

        val o = new Opportunity();
        o.setContact(c);
        o.setSite($(new Site(42))); // AmeriGlide
        o.setProductLine($(new ProductLine(6))); // Stair Lifts
        o.setAmount(o.getProductLine().getAverage());
        var now = LocalDateTime.now();
        o.setEstimatedClose(now.plusDays(1).toLocalDate());
        o.setStage(HOT);
        o.setSource(THIRD_PARTY_ONLINE);
        o.setWebhook(webhook);
        o.setWorked(false);
        o.setWebhookKey(params.get("key"));
        o.setAssignedTo(getAgent());
        o.setCreated(now);
        o.setNotes(params.get("notes"));
        create(webhook.getName(), o);

        val json = new JsonMap();
        json.put("contact", c.id);
        json.put("opportunity", o.id);
        respond(response, json);

    }

    private Agent getAgent() {
        return $(new Agent("7000"));
    }

}

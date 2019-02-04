package net.inetalliance.sonar.webhook;

import com.callgrove.obj.*;
import com.callgrove.types.Address;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.funky.Funky;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;

import static com.callgrove.obj.Webhook.*;
import static com.callgrove.types.ContactType.*;
import static com.callgrove.types.SaleSource.*;
import static com.callgrove.types.SalesStage.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.potion.obj.AddressPo.*;
import static net.inetalliance.types.geopolitical.us.State.*;

@WebServlet("/hook/opportunity")
public class QuoteRequest
	extends AngularServlet {

	public Pattern getPattern() {
		return Pattern.compile("/hook/quoteRequest");
	}

	@Override
	protected void post(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {

		@SuppressWarnings("SpellCheckingInspection") final Webhook webhook = $1(
			withApiKey("53d3befd1d210670d99efc615091ad2f")); // magic key for quote request

		Map<String, String> params = request.getParameterMap()
			.entrySet()
			.stream()
			.collect(Funky.toMap(Map.Entry::getKey, e -> e.getValue()[0]));

		if ($1(Opportunity.withWebhook(webhook, params.get("leadKey"))) != null) {
			throw new BadRequestException("Already created opportunity for lead key %s", params.get("leadKey"));
		}

		final Contact c = new Contact();
		final String name = params.get("name");
		String[] split = name.split("[ ]", 2);
		c.setFirstName(split[0]);
		c.setLastName(split.length == 2 ? split[1] : "Unknown");
		final Address shipping = new Address();
		shipping.setPhone(unformatPhoneNumber(params.get("primaryNumber")));
		c.setEmail(params.get("email"));
		c.setContactType(CUSTOMER);
		if (isNotEmpty(shipping.getPhone())) {
			AreaCodeTime areaCode = AreaCodeTime.getAreaCodeTime(shipping.getPhone());
			if (areaCode != null) {
				shipping.setState(areaCode.getUsState());
			}
		}
		shipping.setState(fromAbbreviation(params.get("state")));
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
		o.setWebhookKey(params.get("key"));
		o.setAssignedTo(getAgent());
		o.setCreated(new DateTime());
		o.setNotes(params.get("notes"));
		create(webhook.getName(), o);

		final JsonMap json = new JsonMap();
		json.put("contact", c.id);
		json.put("opportunity", o.id);
		respond(response, json);

	}

	private Agent getAgent() {
		return $(new Agent("7000"));
	}

}

package net.inetalliance.sonar.webhook;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Relation;
import com.callgrove.obj.Site;
import com.callgrove.types.Address;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebServlet("/hook/facebookLead")
public class FacebookLead
  extends AngularServlet
  implements Dispatchable {

  private static final Pattern phonePattern = Pattern.compile(".*(\\d{10})");
  private static final Random random = new Random();

  @Override
  public Pattern getPattern() {
    return Pattern.compile("/hook/facebookLead");
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
//    final Slack slack = Slack.getInstance();
//    slack.send(slackHook, Payload.builder()
//      .username("erik")
//      .text("Whatsup")
//      .build());
    response.getWriter().println("Use POST, dummy.");
  }

  private static String extractPhone(final String value) {
    final Matcher matcher = phonePattern.matcher(value);
    return matcher.matches() ? null : matcher.group(1);
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    final JsonMap json = JsonMap.parse(request.getInputStream());
    System.out.println("Received: " + Json.F.pretty.$(json));

    final Agent[] agents = new Agent[]{
      new Agent("7108"), // Sean Graham
      new Agent("7501")  // Chris Johnson
    };

    final String fullName = json.get("fullName");
    final Contact contact = new Contact();
    String[] split = fullName.split("[ ]", 2);
    contact.setFirstName(split[0]);
    contact.setLastName(split[1]);
    final Address address = new Address();
    address.setPhone(extractPhone(json.get("phone")));
    contact.setBilling(address);
    contact.setShipping(address);
    contact.setEmail(json.get("email"));
    Locator.create("FacebookLead", contact);

    final Site site = Locator.$1(Site.Q.withAbbreviation(json.get("site")));
    final Agent agent = agents[random.nextInt(agents.length)];
    final Currency amount = new Currency(json.getDouble("amount"));

    final Opportunity opp = new Opportunity();
    opp.setAssignedTo(agent);
    opp.setSource(SaleSource.FACEBOOK);
    opp.setAmount(amount);
    opp.setStage(SalesStage.HOT);
    opp.setContact(contact);
    opp.setPurchasingFor(Relation.SELF);
    opp.setSite(site);
    opp.setCreated(json.getDateTime("date"));
    opp.setEstimatedClose(new DateMidnight());
    Locator.create("FacebookLead", opp);
    final JsonMap result = new JsonMap()
      .$("contact", contact.getId())
      .$("agent", agent.getSlackName())
      .$("opp", opp.getId());
    System.out.println("Responded: " + Json.F.pretty.$(result));
    respond(response, result);
  }
}

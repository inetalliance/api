package net.inetalliance.sonar.webhook;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Relation;
import com.callgrove.obj.Site;
import com.callgrove.types.Address;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.log.Log;
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

  private static final transient Log log = Log.getInstance(FacebookLead.class);
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
  protected void post(final HttpServletRequest request, final HttpServletResponse response) {
    try {
      final JsonMap json = JsonMap.parse(request.getInputStream());
      System.out.println("Received: " + Json.F.pretty.$(json));

      System.out.println(1);
      final Agent[] agents = new Agent[]{
        new Agent("7108"), // Sean Graham
        new Agent("7501")  // Chris Johnson
      };

      final String fullName = json.get("fullName");
      final Contact contact = new Contact();
      String[] split = fullName.split("[ ]", 2);
      contact.setFirstName(split[0]);
      contact.setLastName(split[1]);
      contact.setContactType(ContactType.CUSTOMER);
      System.out.println(2);
      final Address address = new Address();
      address.setPhone(extractPhone(json.get("phone")));
      contact.setBilling(address);
      contact.setShipping(address);
      contact.setEmail(json.get("email"));
      System.out.println(3);
      Locator.create("FacebookLead", contact);
      System.out.println(4);

      final Site site = Locator.$1(Site.Q.withAbbreviation(json.get("site")));
      System.out.println(5);
      final Agent agent = agents[random.nextInt(agents.length)];
      System.out.println(6);
      final Currency amount = new Currency(json.getDouble("amount"));
      System.out.println(7);

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
      System.out.println(8);
      Locator.create("FacebookLead", opp);
      System.out.println(9);
      final JsonMap result = new JsonMap()
        .$("contact", contact.getId())
        .$("agent", agent.getSlackName())
        .$("opp", opp.getId());
      System.out.println("Responded: " + Json.F.pretty.$(result));
      respond(response, result);
    } catch (Throwable e) {
      log.error(e);
    }
  }
}

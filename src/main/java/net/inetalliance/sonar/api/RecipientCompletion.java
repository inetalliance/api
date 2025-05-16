package net.inetalliance.sonar.api;

import static java.util.Collections.singleton;
import static java.util.Comparator.comparing;
import static net.inetalliance.potion.Locator.$$;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Site;
import java.util.Collection;
import java.util.regex.Pattern;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;

@WebServlet("/api/recipient")
public class RecipientCompletion
    extends AngularServlet {

  private static final Pattern atSign = Pattern.compile("@");

  private static JsonMap contactToJson(final Contact contact) {
    return new JsonMap().$("id", contact.id)
        .$("type", "contact")
        .$("email", contact.getEmail())
        .$("name", contact.getFullName());
  }

  private static String agentEmailWithDomain(final Agent agent, final String domain) {
    if (domain == null) {
      return agent.getEmail();
    } else {
      final String userName = atSign.split(agent.getEmail(), 2)[0];
      return userName + '@' + domain;
    }
  }

  private static JsonMap agentToJson(final Site site, final Agent agent) {
    return new JsonMap().$("id", agent.key)
        .$("type", "agent")
        .$("email", agentEmailWithDomain(agent, site == null ? null : site.getDomain()))
        .$("name", agent.getFullName());
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final Authorized authorized = Auth.getAuthorized(request);
    if (authorized == null) {
      throw new UnauthorizedException("Not logged in");
    }
    final String search = request.getParameter("q");
    if (search == null || search.length() == 0) {
      throw new BadRequestException("Must be called with a \"q\" parameter");
    }
    final int siteId = getParameter(request, "site", 0);
    final Site site;
    if (siteId > 0) {
      site = Locator.$(new Site(siteId));
      if (site == null) {
        throw new NotFoundException("Cannot find site: " + siteId);
      }
    } else {
      site = null;
    }

    final Agent agent = new Agent(Auth.getAuthorized(request).getPhone());
    //    final Agent agent = new Agent("7211"); // Timmy Z

    // FIND CONTACTS
    final Query<Contact> contactMatches = Contact.withNameLike(search + "%")
        .or(Contact.withEmailLike(search + "%"));
    Query<Contact> contactQuery =
        Contact.hasEmail
            .and(Contact.inOppsWithAgent(agent).and(contactMatches).and(Contact.notAgentEmail));
    if (site != null) {
      contactQuery = contactQuery.and(Contact.withOppsOn(singleton(site)));
    }
    final Collection<Contact> contacts = $$(contactQuery);

    // FIND AGENTS
    final Collection<Agent> agents = $$(
        Agent.withNameLike(search + "%").or(Agent.withEmailLike(search + "%")));

    final JsonList results = JsonList.collect(contacts, RecipientCompletion::contactToJson);
    agents.stream().map(a -> agentToJson(site, a)).forEach(results::add);
    results.sort(comparing(j -> ((JsonMap) j).get("name")));

    respond(response, Listable.formatResult(results));
  }
}

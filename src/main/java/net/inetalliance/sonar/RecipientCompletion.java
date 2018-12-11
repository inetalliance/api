package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Site;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Pattern;

import static java.util.Collections.singleton;
import static net.inetalliance.potion.Locator.$$;

@WebServlet("/api/recipient")
public class RecipientCompletion
		extends AngularServlet {
	private static final Pattern atSign = Pattern.compile("@");

	private static final F1<Contact, JsonMap> contactToJson = new F1<Contact, JsonMap>() {
		@Override
		public JsonMap $(final Contact contact) {
			return new JsonMap()
					.$("id", contact.id)
					.$("type", "contact")
					.$("email", contact.getEmail())
					.$("name", contact.getFullName());
		}
	};

	private static F1<Agent, String> agentEmailWithDomain(final String domain) {
		return new F1<Agent, String>() {
			@Override
			public String $(final Agent agent) {
				if (domain == null) {
					return agent.getEmail();
				} else {
					final String userName = atSign.split(agent.getEmail(), 2)[0];
					return userName + '@' + domain;
				}
			}
		};
	}

	private static F1<Agent, JsonMap> agentToJson(final Site site) {
		final F1<Agent, String> getEmail = agentEmailWithDomain(site == null ? null : site.getDomain());
		return new F1<Agent, JsonMap>() {
			@Override
			public JsonMap $(final Agent agent) {
				return new JsonMap()
						.$("id", agent.key)
						.$("type", "agent")
						.$("email", getEmail.$(agent))
						.$("name", agent.getFullName());
			}
		};
	}

	private static final Comparator<Json> byName = Json.F.byString("name");

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
		Site site = null;
		if (siteId > 0) {
			site = Locator.$(new Site(siteId));
			if (site == null) {
				throw new NotFoundException("Cannot find site: " + siteId);
			}
		}

		final Agent agent = new Agent(Auth.getAuthorized(request).getPhone());
//    final Agent agent = new Agent("7211"); // Timmy Z

		// FIND CONTACTS
		final Query<Contact> contactMatches =
				Contact.Q.withNameLike(search + "%")
						.or(Contact.Q.withEmailLike(search + "%"));
		Query<Contact> contactQuery = Contact.Q.hasEmail
				.and(Contact.Q.inOppsWithAgent(agent)
						.and(contactMatches)
						.and(Contact.Q.notAgentEmail));
		if (site != null) {
			contactQuery = contactQuery.and(Contact.Q.withOppsOn(singleton(site)));
		}
		final Collection<Contact> contacts = $$(contactQuery);

		// FIND AGENTS
		final Collection<Agent> agents =
				$$(Agent.Q.withNameLike(search + "%")
						.or(Agent.Q.withEmailLike(search + "%")));

		final JsonList results = new JsonList(contacts.size() + agents.size());
		results.addAll(contactToJson.map(contacts));
		results.addAll(agentToJson(site).map(agents));
		Collections.sort(results, byName);

		respond(response, ListableModel.Impl.formatResult(results));
	}
}

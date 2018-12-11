package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;

import javax.servlet.ServletConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

import static net.inetalliance.funky.functors.types.str.StringFun.empty;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.$;

@WebServlet({"/login", "/logout"})
public class AgentAuth extends Auth {


	private DefaultHttpClient client;

	public AgentAuth() {
		super();
	}

	protected Json toJson(final HttpServletRequest request, final Authorized authorized) {
		final Object nylasAccount = request.getSession().getAttribute("nylasAccount");
		if (nylasAccount == null) {
			log.warning("No mail account for %s (sudo?)", authorized.getName());
		}
		return Info.$(Agent.class)
			.toJson($(new Agent(authorized.getPhone())))
			.$("nylasAccount", nylasAccount == null ? null : nylasAccount.toString())
			.$("roles", JsonList.$(authorized.getRoles()));
	}


	private JsonMap postNylas(final String endpoint, final JsonMap data) {
		final HttpPost post = new HttpPost(String.format("https://api.nylas.com%s", endpoint));
		post.setEntity(new StringEntity(Json.F.pretty.$(data), ContentType.APPLICATION_JSON));
		try {
			final HttpResponse res = client.execute(post);
			return JsonMap.parse(res.getEntity().getContent());
		} catch (IOException e) {
			log.error(e);

		} finally {
			post.releaseConnection();
		}
		return null;
	}

	private String getCode(final Agent agent, final String password, final boolean forceCodeLookup) {
		if (!forceCodeLookup && !empty.$(agent.getNylasCode())) {
			return agent.getNylasCode();
		}
		final JsonMap json = new JsonMap()
			.$("client_id", "ccp8e4h3wu7i4s2o5mdi3hlne")
			.$("name", agent.getFullName())
			.$("email_address", agent.getEmail())
			.$("provider", "imap")
			.$("settings", new JsonMap()
				.$("imap_host", "mail.inetalliance.net")
				.$("imap_port", 993)
				.$("imap_username", agent.getEmail())
				.$("imap_password", password)
				.$("smtp_host", "mail.inetalliance.net")
				.$("smtp_username", agent.getEmail())
				.$("smtp_password", password)
				.$("ssl_required", true));
		final JsonMap account = postNylas("/connect/authorize", json);
		if (account != null) {
			final String nylasCode = account.get("code");
			if (!empty.$(nylasCode)) {
				Locator.update(agent, "AgentAuth", new P1<Agent>() {
					@Override
					public void $(Agent agent) {
						agent.setNylasCode(nylasCode);
					}
				});
			}
		}
		return agent.getNylasCode();
	}

	@Override
	protected void onLogin(final HttpServletRequest request, final Authorized authorized) {
		onLogin(request, authorized, false);

	}

	private void onLogin(final HttpServletRequest request, final Authorized authorized, final boolean forceCodeLookup) {
		final String password = request.getParameter("password");
		final Agent agent = Locator.$(new Agent(authorized.getPhone()));
		final String nylasCode = getCode(agent, password, forceCodeLookup);
		if (!empty.$(nylasCode)) {
			final JsonMap json = postNylas("/connect/token", new JsonMap()
				.$("client_id", "ccp8e4h3wu7i4s2o5mdi3hlne")
				.$("client_secret", "f02ufhmcopkrzbc35ah6w7wto")
				.$("code", agent.getNylasCode()));
			if (json != null) {
				final String token = json.get("access_token");
				if (empty.$(token)) {
					if ("api_error".equals(json.get("type"))
						&& json.get("message").startsWith("No grant with code")
						&& !forceCodeLookup) {
						onLogin(request, authorized, true);
					}
				} else {
					request.getSession().setAttribute("nylasAccount", token);
				}
			}
		}
	}

	@Override
	public void init(final ServletConfig config) {
		this.client = new DefaultHttpClient(new PoolingClientConnectionManager());
	}


	@Override
	public void destroy() {
		client.getConnectionManager().shutdown();
	}

	private static final transient Log log = getInstance(AgentAuth.class);

}

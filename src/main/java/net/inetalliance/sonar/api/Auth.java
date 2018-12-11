package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.util.security.auth.Authorized;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.potion.Locator.*;

@WebServlet({"/reporting/login", "/reporting/logout"})
public class Auth
  extends net.inetalliance.angular.auth.Auth {

	public Auth() {
		super();
	}

	protected Json toJson(final HttpServletRequest request, final Authorized authorized) {
		return Info.$(Agent.class)
				.toJson($(new Agent(authorized.getPhone())))
				.$("roles", JsonList.$(authorized.getRoles()));
	}
}

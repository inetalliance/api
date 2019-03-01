package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.types.json.*;
import net.inetalliance.util.security.auth.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;

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
		           .$("roles", JsonList.collect(authorized.getRoles(), JsonString::new));
	}
}

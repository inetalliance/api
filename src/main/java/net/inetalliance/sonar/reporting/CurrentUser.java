package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import net.inetalliance.angular.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.sonar.api.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;

@WebServlet("/reporting/currentUser")
public class CurrentUser
		extends AngularServlet {

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Agent loggedIn = Startup.getAgent(request);
		respond(response, loggedIn == null ? null : Info.$(Agent.class).toJson(loggedIn));
	}
}

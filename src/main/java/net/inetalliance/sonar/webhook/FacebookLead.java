package net.inetalliance.sonar.webhook;

import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;

@WebServlet("/hook/facebookLead")
public class FacebookLead
		extends AngularServlet
		implements Dispatchable {

	@Override
	public Pattern getPattern() {
		return Pattern.compile("/hook/facebookLead");
	}

  @Override
	protected void post(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
    final JsonMap json = JsonMap.parse(request.getInputStream());
    System.out.println(Json.F.pretty.$(json));
    respond(response, null);
	}

}

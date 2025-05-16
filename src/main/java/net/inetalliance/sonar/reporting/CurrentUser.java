package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.info.Info;
import net.inetalliance.sonar.api.Startup;

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

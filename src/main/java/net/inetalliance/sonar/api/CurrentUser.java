package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.info.Info;
import net.inetalliance.sonar.Startup;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/reporting/currentUser")
public class CurrentUser extends AngularServlet {

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    final Agent loggedIn = Startup.getAgent(request);
    respond(response, loggedIn == null ? null : Info.$(Agent.class).toJson(loggedIn));
  }
}

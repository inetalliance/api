package net.inetalliance.sonar.api;

import com.callgrove.obj.Opportunity;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.MethodNotAllowedException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.Avochato;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebServlet("/api/textHistory/*")
public class TextHistory
  extends AngularServlet
  implements Dispatchable {
  private static final Pattern pattern = Pattern.compile("/api/textHistory/([0-9]+)");

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    throw new MethodNotAllowedException();
  }

  @Override
  protected void delete(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    throw new MethodNotAllowedException();
  }

  @Override
  protected void put(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    throw new MethodNotAllowedException();
  }

  @Override
  public Pattern getPattern() {
    return pattern;
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    final String query = request.getParameter("q");
    Matcher matcher = pattern.matcher(request.getRequestURI());
    if (matcher.matches()) {
      final int opportunityId = Integer.parseInt(matcher.group(1));
      final Opportunity opportunity = Locator.$(new Opportunity(opportunityId));
      if (opportunity == null) {
        throw new NotFoundException();
      }
      respond(response, Avochato.getInstance().searchContacts(opportunity));
    } else {
      throw new BadRequestException("Request should match %s",
        new Object[]{this.pattern.pattern()});
    }
  }
}

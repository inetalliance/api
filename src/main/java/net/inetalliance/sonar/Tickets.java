package net.inetalliance.sonar;

import com.callgrove.obj.Opportunity;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.Pretty;
import net.inetalliance.types.www.ContentType;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Integer.valueOf;
import static java.util.regex.Pattern.compile;
import static net.inetalliance.potion.Locator.$;

@WebServlet("/api/tickets/*")
public class Tickets
  extends AngularServlet
  implements Dispatchable {
  public static final Pattern pattern = compile("/api/tickets/(.*)");
  public static final String api = "https://youtrack.inetalliance.net/rest";
  private YouTrack youTrack;

  @Override
  public Pattern getPattern() {
    return pattern;
  }

  @Override
  public void init(final ServletConfig config)
    throws ServletException {
    youTrack = new YouTrack();
  }

  @Override
  public void destroy() {
    super.destroy();
    youTrack.shutdown();
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    final Matcher matcher = pattern.matcher(request.getRequestURI());
    if (matcher.matches()) {
      final Opportunity opp = $(new Opportunity(valueOf(matcher.group(1))));
      if(opp == null) {
        throw new NotFoundException();
      }
      final JsonMap json =
        youTrack.get("/issue?filter=project%3A%7BAmeriGlide+Customer+Service%7D+Opportunity+ID%3A+" + opp.getId());
      response.setContentType(ContentType.JSON.toString());
      Pretty.$(json,response.getWriter());
      response.flushBuffer();
      response.setStatus(200);
    } else {
      throw new BadRequestException("request URI should match %s", pattern.pattern());
    }
  }
}

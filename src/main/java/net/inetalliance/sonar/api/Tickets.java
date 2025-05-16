package net.inetalliance.sonar.api;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.Pretty;
import net.inetalliance.types.www.ContentType;

import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

@WebServlet("/api/tickets/*")
public class Tickets
        extends AngularServlet {

    public static final Pattern pattern = compile("/api/tickets/(.*)");
    public static final String api = "https://youtrack.inetalliance.net/rest";

    public Pattern getPattern() {
        return pattern;
    }

    @Override
    public void init(final ServletConfig config) {
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        response.setContentType(ContentType.JSON.toString());
        Pretty.$(new JsonMap().$("issue", JsonList.empty), response.getWriter());
        response.flushBuffer();
        response.setStatus(200);
    }
}

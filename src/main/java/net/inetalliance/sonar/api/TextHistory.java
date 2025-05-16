package net.inetalliance.sonar.api;

import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.MethodNotAllowedException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.Avochato;

import java.util.regex.Pattern;

@WebServlet("/api/textHistory/*")
public class TextHistory
        extends AngularServlet {

    private static final Pattern pattern = Pattern.compile("/api/textHistory/([0-9]+)");

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) {
        throw new MethodNotAllowedException();
    }

    @Override
    protected void delete(final HttpServletRequest request, final HttpServletResponse response) {
        throw new MethodNotAllowedException();
    }

    @Override
    protected void put(final HttpServletRequest request, final HttpServletResponse response) {
        throw new MethodNotAllowedException();
    }

    public Pattern getPattern() {
        return pattern;
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.matches()) {
            val opportunityId = Integer.parseInt(matcher.group(1));
            val opportunity = Locator.$(new Opportunity(opportunityId));
            if (opportunity == null) {
                throw new NotFoundException();
            }
            respond(response, Avochato.getInstance().searchContacts(opportunity));
        } else {
            throw new BadRequestException("Request should match %s", pattern.pattern());
        }
    }
}

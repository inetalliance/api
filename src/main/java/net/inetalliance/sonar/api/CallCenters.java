package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Optionals;
import com.callgrove.obj.CallCenter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.sonar.ListableModel;

import static java.util.stream.Collectors.toList;

@WebServlet("/api/callCenter/*")
public class CallCenters
        extends ListableModel<CallCenter> {

    public CallCenters() {
        super(CallCenter.class);
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        if ("TRUE".equalsIgnoreCase(request.getParameter("visible"))) {
            respond(response, Listable.formatResult(
                    Iterables.stream(Optionals.of(Startup.getAgent(request)).orElseThrow().getViewableCallCenters())
                            .filter(CallCenter.withSalesAgent)
                            .map(c -> toJson(request, c))
                            .collect(toList())));
        }
        super.get(request, response);
    }

}

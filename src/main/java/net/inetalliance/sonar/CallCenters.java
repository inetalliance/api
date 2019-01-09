package net.inetalliance.sonar;

import com.callgrove.obj.CallCenter;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.funky.Funky;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.stream.Collectors;


@WebServlet("/api/callCenter/*")
public class CallCenters extends ListableModel<CallCenter> {
	public CallCenters() {
		super(CallCenter.class);
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		if ("TRUE".equalsIgnoreCase(request.getParameter("visible"))) {
			respond(response, Listable.formatResult(
				Funky.stream(Optional.ofNullable(Startup.getAgent(request)).orElseThrow().getViewableCallCenters())
					.filter(CallCenter.withSalesAgent)
					.map(c -> toJson(request, c)).collect(Collectors.toList())));
		}
		super.get(request, response);
	}

}

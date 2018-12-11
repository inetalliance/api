package net.inetalliance.sonar;

import com.callgrove.obj.CallCenter;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static net.inetalliance.angular.list.Listable.Impl.formatResult;

@WebServlet("/api/callCenter/*")
public class CallCenters extends ListableModel<CallCenter> {
	public CallCenters() {
		super(CallCenter.class);
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		if ("TRUE".equalsIgnoreCase(request.getParameter("visible"))) {
			respond(response, formatResult(toJson(request).copy(CallCenter.Q.withSalesAgent.filter(Startup.getAgent(request)
					.getViewableCallCenters()))));
		}
		super.get(request, response);
	}

}

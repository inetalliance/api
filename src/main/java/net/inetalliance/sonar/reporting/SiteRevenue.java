package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;

@WebServlet("/reporting/reports/siteRevenue")
public class SiteRevenue
		extends Revenue<Site> {

	@Override
	protected JsonMap addRowInfo(final JsonMap json, final Site site) {
		return json.$("site", site.id);
	}

	@Override
	protected Query<Opportunity> oppsForRow(final Site row) {
		return Opportunity.withSite(row);
	}

	@Override
	protected String getId(final Site site) {
		return site.id.toString();
	}

	@Override
	protected Query<Site> allRows(final Agent loggedIn, final DateTime intervalStart) {
		return Site.isActive;
	}
}

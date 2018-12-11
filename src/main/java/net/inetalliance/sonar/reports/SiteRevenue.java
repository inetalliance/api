package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;

@WebServlet("/api/siteRevenue")
public class SiteRevenue
		extends Revenue<Site> {

	@Override
	protected JsonMap addRowInfo(final JsonMap json, final Site site) {
		return json.$("site", site.id);
	}

	@Override
	protected Query<Site> allRows(final Agent loggedIn) {
		return Site.Q.isActive;
	}

	@Override
	protected String getId(final Site site) {
		return site.id.toString();
	}

	@Override
	protected Query<Opportunity> oppsForRow(final Site row) {
		return Opportunity.Q.withSite(row);
	}
}

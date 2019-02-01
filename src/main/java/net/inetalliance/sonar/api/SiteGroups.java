package net.inetalliance.sonar.api;

import com.callgrove.obj.SiteGroup;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/siteGroup/*")
public class SiteGroups
	extends ListableModel.Named<SiteGroup> {
	public SiteGroups() {
		super(SiteGroup.class);
	}

	@Override
	public Query<SiteGroup> all(final Class<SiteGroup> type, final HttpServletRequest request) {
		return super.all(type, request).orderBy("name", ASCENDING);
	}
}

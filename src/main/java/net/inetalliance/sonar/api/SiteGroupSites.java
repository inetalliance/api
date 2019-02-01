package net.inetalliance.sonar.api;

import com.callgrove.obj.Site;
import com.callgrove.obj.SiteGroup;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonInteger;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet({"/api/siteGroup/site", "/reporting/siteGroup/site"})
public class SiteGroupSites
	extends SelectMembers<SiteGroup, Site> {
	public SiteGroupSites() {
		super(SiteGroup.class, Site.class);
	}

	@Override
	public Query<SiteGroup> all(final Class<SiteGroup> type, final HttpServletRequest request) {
		return Startup.getAgent(request).getVisibleSiteGroupsQuery().and(SiteGroup.isSelectable).orderBy("name",
				ASCENDING);
	}

	@Override
	protected JsonInteger toId(final Site member) {
		return new JsonInteger(member.getId());
	}

	@Override
	protected String getLabel(final Site member) {
		return member.getAbbreviation().toString();
	}

	@Override
	protected Collection<Site> getMembers(final SiteGroup group) {
		return group.getSites();
	}
}

package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;

import java.time.LocalDateTime;
import java.util.Set;
import jakarta.servlet.annotation.WebServlet;
import net.inetalliance.beejax.messages.Category;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;

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
  protected Query<Site> allRows(final Set<Category> groups, final Agent loggedIn,
      final LocalDateTime intervalStart) {
    return Site.isActive;
  }
}

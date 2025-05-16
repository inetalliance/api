package net.inetalliance.sonar.api;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.SiteGroup;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;

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

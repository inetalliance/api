package net.inetalliance.sonar.api;

import com.callgrove.obj.SkillRoute;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/skillRoute/*")
public class SkillRoutes
    extends ListableModel.Named<SkillRoute> {

  public SkillRoutes() {
    super(SkillRoute.class);
  }

  @Override
  public Query<SkillRoute> all(final Class<SkillRoute> type, final HttpServletRequest request) {
    return super.all(type, request).orderBy("name", ASCENDING);
  }
}

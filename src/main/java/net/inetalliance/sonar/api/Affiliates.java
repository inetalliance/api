package net.inetalliance.sonar.api;

import com.callgrove.obj.Affiliate;
import com.callgrove.obj.Agent;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;

@WebServlet("/api/affiliate/*")
public class Affiliates
    extends ListableModel.Named<Affiliate> {

  public Affiliates() {
    super(Affiliate.class);
  }

  @Override
  public Query<Affiliate> all(Class<Affiliate> type, HttpServletRequest request) {
    final Agent agent = Startup.getAgent(request);
    if(agent == null) {
      throw new UnauthorizedException();
    }
    return Affiliate.visible(agent);
  }
}



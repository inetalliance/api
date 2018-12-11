package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;

import javax.servlet.annotation.WebServlet;
import java.util.Set;

@WebServlet("/api/productLineSalesCycle")
public class ProductLineSalesCycle
  extends SalesCycle<Agent, ProductLine> {
  private static final F1<String, ProductLine> lookup = Info.$(ProductLine.class).lookup;


  public ProductLineSalesCycle() {
    super("productLine");
  }

  @Override
  protected F1<String, ProductLine> getGroupLookup(final String[] params) {
    return lookup;
  }

  @Override
  protected Query<Agent> allRows(final Agent loggedIn) {
    return loggedIn.getViewableAgentsQuery().and(Agent.Q.sales.and(Agent.Q.locked.negate()));
  }

  @Override
  protected Query<Call> callWithRow(final Agent row) {

    return Call.Q.withAgent(row);
  }

  @Override
  protected String getGroupLabel(final ProductLine group) {
    return group.getAbbreviation().toString();
  }

  @Override
  protected String getId(final Agent row) {
    return row.key;
  }

  @Override
  protected String getLabel(final Agent row) {
    return row.getLastNameFirstInitial();
  }

  @Override
  protected Query<Opportunity> inGroups(final Set<ProductLine> groups) {
    return Opportunity.Q.withProductLineIn(IdPo.F.id.map(groups));
  }

  @Override
  protected Query<DailyPerformance> performanceForGroups(final Set<ProductLine> groups) {
    return DailyPerformance.Q.withProductLineIn(groups);
  }

  @Override
  protected Query<DailyPerformance> performanceWithRow(final Agent row) {
    return DailyPerformance.Q.withAgent(row);
  }

  @Override
  protected Query<Opportunity> withRow(final Agent row) {
    return Opportunity.Q.withAgent(row);
  }
}

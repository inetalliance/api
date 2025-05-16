package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import jakarta.servlet.annotation.WebServlet;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;

import java.time.LocalDateTime;
import java.util.Set;

@WebServlet("/reporting/reports/productLineSalesCycle")
public class ProductLineSalesCycle
    extends SalesCycle<Agent, ProductLine> {

  public ProductLineSalesCycle() {
    super("productLine");
  }

  @Override
  protected Query<Call> callWithRow(final Agent row) {

    return Call.withAgent(row);
  }

  @Override
  protected Query<Opportunity> inGroups(final Set<ProductLine> groups) {
    return Opportunity.withProductLineIn(groups);
  }

  @Override
  protected String getLabel(final Agent row) {
    return row.getLastNameFirstInitial();
  }

  @Override
  protected Query<Opportunity> withRow(final Agent row) {
    return Opportunity.withAgent(row);
  }

  @Override
  protected Query<DailyPerformance> performanceForGroups(final Set<ProductLine> groups) {
    return DailyPerformance.withProductLineIn(groups);
  }

  @Override
  protected Query<DailyPerformance> performanceWithRow(final Agent row) {
    return DailyPerformance.withAgent(row);
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
  protected Query<Agent> allRows(final Set<ProductLine> groups, final Agent loggedIn,
                                 final LocalDateTime intervalStart) {
    return loggedIn.getViewableAgentsQuery().and(Agent.activeAfter(intervalStart))
        .and(Agent.isSales);
  }

  @Override
  protected ProductLine getGroup(String[] params, String g) {
    return Info.$(ProductLine.class).lookup(g);
  }
}

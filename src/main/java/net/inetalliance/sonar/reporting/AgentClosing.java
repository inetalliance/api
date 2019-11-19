package net.inetalliance.sonar.reporting;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.isOutbound;
import static com.callgrove.obj.Call.isQueue;
import static com.callgrove.obj.Call.withBlame;
import static com.callgrove.obj.Opportunity.soldInInterval;
import static com.callgrove.obj.Opportunity.withAmountGreaterThan;
import static com.callgrove.obj.Opportunity.withProductLine;
import static com.callgrove.types.SaleSource.ONLINE;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.countDistinct;
import static net.inetalliance.sql.Aggregate.SUM;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.DailyPerformance;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Queue;
import com.callgrove.obj.Segment;
import com.callgrove.obj.Site;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.annotation.WebServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.sql.AggregateField;
import net.inetalliance.sql.ColumnWhere;
import net.inetalliance.sql.SqlBuilder;
import net.inetalliance.sql.SubqueryValueWhere;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

@WebServlet({"/reporting/reports/agentClosing"})
public class AgentClosing
    extends CachedGroupingRangeReport<Agent, Site> {

  private static final transient Log log = getInstance(AgentClosing.class);
  private final Info<Site> info;

  public AgentClosing() {
    super("site", "agent", "uniqueCid", "noTransfers");
    info = Info.$(Site.class);
  }

  @Override
  protected String getGroupLabel(final Site group) {
    return group.getName();
  }

  @Override
  protected String getId(final Agent row) {
    return row.key;
  }

  @Override
  protected Query<Agent> allRows(Set<Site> groups, final Agent loggedIn,
      final DateTime intervalStart) {
    return loggedIn.getViewableAgentsQuery(false).and(Agent.activeAfter(intervalStart))
        .and(Agent.isSales);
  }

  @Override
  protected Site getGroup(final String[] params, final String key) {
    return info.lookup(key);
  }

  @Override
  protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
      final Interval intervalStart) {
    return count(allRows(groups, loggedIn, intervalStart.getStart()));
  }

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<Site> sites, Collection<CallCenter> callCenters,
      final Map<String, String[]> extras) {
    if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      log.warning("%s tried to access closing report data",
          loggedIn == null ? "Nobody?" : loggedIn.key);
      throw new UnauthorizedException();
    }
    final String agentKey = getSingleExtra(extras, "agent", "");
    if (isEmpty(agentKey)) {
      throw new BadRequestException("Must specify agent via ?agent=");
    }
    final Agent agent = $(new Agent(agentKey));
    if (agent == null) {
      throw new NotFoundException("Could not find agent with key %s", agentKey);
    }

    final Query<Opportunity> oppSources;
    final Query<Call> callSources;

    if (sources.isEmpty()) {
      oppSources = Opportunity.isOnline.negate();
      callSources = Call.withSourceIn(EnumSet.of(ONLINE)).negate();
    } else {
      oppSources = Opportunity.withSources(sources);
      callSources = Call.withSourceIn(sources);
    }

    boolean uniqueCid = Boolean.valueOf(
        getSingleExtra(extras, "uniqueCid", "false"));
    boolean noTransfers = Boolean.valueOf(
        getSingleExtra(extras, "noTransfers", "false"));

    final Interval interval = getReportingInterval(start, end);

    final Set<String> queues = Locator.$A(Queue.class).stream().map(q -> q.key).collect(toSet());
    ProductLineClosing.retainVisible(loggedIn, sites, queues);

    final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
    final Query<Call> callQuery = Call.inInterval(interval);
    final Query<Opportunity> oppQuery = soldInInterval(interval).and(Opportunity.withAgent(agent))
        .and(oppSources)
        .and(Opportunity.withContactTypes(contactTypes))
        .and(sites == null || sites.isEmpty() ? Query.all(
            Opportunity.class) :
            Opportunity.withSiteIn(sites));
    final JsonList rows = new JsonList();
    Locator.forEach(Query.all(ProductLine.class), productLine -> {

      final AtomicInteger n = new AtomicInteger(0);
      final Set<String> productLineQueues = new HashSet<>(queues);
      productLineQueues.retainAll(Startup.productLineQueues.getOrDefault(productLine.id, Set.of()));
      final Query<Call> productLineCallQuery =
          productLineQueues.isEmpty() ? Query.none(Call.class)
              : callQuery.and(Call.withQueueIn(productLineQueues));
      final DailyPerformance productLineTotal = new DailyPerformance();
      Query<Call> queueQuery = productLineCallQuery.and(isQueue).and(callSources)
          .and(withBlame(agent));
      if (noTransfers) {
        queueQuery = noTransfers(queueQuery);

      }
      productLineTotal.setQueueCalls(
          uniqueCid ? countDistinct(queueQuery, "callerId_number") : count(queueQuery));

      final Query<Call> outboundQuery = productLineCallQuery.and(isOutbound)
          .and(Call.withAgent(agent));
      if (uniqueCid) {
        productLineTotal.setOutboundCalls(
            countDistinct(outboundQuery.join(Segment.class, "call"), "segment.callerId_number"));

      } else {
        productLineTotal.setOutboundCalls(count(outboundQuery));
      }
      productLineTotal.setDumps(
          count(productLineCallQuery.and(isQueue)
              .and(Call.withAgent(agent).negate())
              .and(Call.isDumped)
              .and(Call.withBlame(agent))));
      final Query<Opportunity> agentOppQuery = oppQuery.and(
          withProductLine(productLine)
              .and(withAmountGreaterThan(productLine.getLowestReasonableAmount())));
      productLineTotal.setCloses(count(agentOppQuery));
      productLineTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
      if (productLineTotal.getCloses() > 0 || productLineTotal.getQueueCalls() > 0) {
        n.incrementAndGet();
        rows.add(info.toJson(productLineTotal).$("label", productLine.getName())
            .$("id", productLine.id));
      }
      meter.increment(productLine.getName());
    });
    return new JsonMap().$("rows", rows);

  }
  static Query<Call> noTransfers(Query<Call> queueQuery) {
    return queueQuery.and(
        new Query<>(Call.class,
            c -> Locator.count(Segment.withCall(c)) == 1,
            (namer, s) -> {
              var sql = new SqlBuilder(namer.name(Segment.class), null, new AggregateField(Aggregate.COUNT, "*"));
              sql.where(new ColumnWhere("call", "key", "segment", "call",false));
              return new SubqueryValueWhere(sql.getSql(), 1);
            }));
  }
}

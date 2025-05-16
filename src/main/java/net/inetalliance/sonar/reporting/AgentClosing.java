package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.obj.Queue;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.sql.*;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.*;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.obj.Opportunity.withProductLine;
import static com.callgrove.types.SaleSource.ONLINE;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet({"/reporting/reports/agentClosing"})
public class AgentClosing
        extends CachedGroupingRangeReport<Agent, Site> {

    private static final Log log = new Log();
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
                                   final LocalDateTime intervalStart) {
        return loggedIn.getViewableAgentsQuery(false).and(Agent.activeAfter(intervalStart))
                .and(Agent.isSales);
    }

    @Override
    protected Site getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
                             final DateTimeInterval intervalStart) {
        return count(allRows(groups, loggedIn, intervalStart.start()));
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<Site> sites, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
            log.warn(() -> "%s tried to access closing report data".formatted(
                    loggedIn == null ? "Nobody?" : loggedIn.key));
            throw new UnauthorizedException();
        }
        val agentKey = getSingleExtra(extras, "agent", "");
        if (isEmpty(agentKey)) {
            throw new BadRequestException("Must specify agent via ?agent=");
        }
        val agent = $(new Agent(agentKey));
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

        var uniqueCid = Boolean.parseBoolean(
                getSingleExtra(extras, "uniqueCid", "false"));
        var noTransfers = Boolean.parseBoolean(
                getSingleExtra(extras, "noTransfers", "false"));

        var interval = getReportingInterval(start, end);

        val queues = Locator.$A(Queue.class).stream().map(q -> q.key).collect(toSet());
        ProductLineClosing.retainVisible(loggedIn, sites, queues);

        val info = Info.$(DailyPerformance.class);
        val callQuery = Call.inInterval(interval);
        val oppQuery = soldInInterval(interval).and(Opportunity.withAgent(agent))
                .and(oppSources)
                .and(Opportunity.withContactTypes(contactTypes))
                .and(sites == null || sites.isEmpty() ? Query.all(
                        Opportunity.class) :
                        Opportunity.withSiteIn(sites));
        val rows = new JsonList();
        Locator.forEach(Query.all(ProductLine.class), productLine -> {

            val n = new AtomicInteger(0);
            final Set<String> productLineQueues = new HashSet<>(queues);
            productLineQueues.retainAll(Startup.productLineQueues.getOrDefault(productLine.id, Set.of()));
            val productLineCallQuery =
                    productLineQueues.isEmpty() ? Query.none(Call.class)
                            : callQuery.and(Call.withQueueIn(productLineQueues));
            val productLineTotal = new DailyPerformance();
            var queueQuery = productLineCallQuery.and(isQueue).and(callSources)
                    .and(withBlame(agent));
            if (noTransfers) {
                queueQuery = noTransfers(queueQuery);

            }
            productLineTotal.setQueueCalls(
                    uniqueCid ? countDistinct(queueQuery, "callerId_number") : count(queueQuery));

            val outboundQuery = productLineCallQuery.and(isOutbound)
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
            val agentOppQuery = oppQuery.and(
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
                        (namer, _) -> {
                            var sql = new SqlBuilder(namer.name(Segment.class), null, new AggregateField(Aggregate.COUNT, "*"));
                            sql.where(new ColumnWhere("call", "key", "segment", "call", false));
                            return new SubqueryValueWhere(sql.getSql(), 1);
                        }));
    }
}

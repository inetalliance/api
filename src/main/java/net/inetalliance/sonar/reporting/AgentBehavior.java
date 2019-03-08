package net.inetalliance.sonar.reporting;

import static java.util.stream.Collectors.toSet;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.schema;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.using;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.types.Address;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.jooq.DSLContext;
import org.jooq.EnumType;
import org.jooq.SQLDialect;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.impl.DSL;

@WebServlet("/reporting/reports/behavior")
public class AgentBehavior
    extends AngularServlet {

  private static LocalDate toLocalDate(final DateMidnight midnight) {
    return LocalDate.of(midnight.getYear(), midnight.getMonthOfYear(), midnight.getDayOfMonth());
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final var interval = Callgrove.getInterval(request);
    final LocalDate start = toLocalDate(interval
        .getStart()
        .toDateMidnight());
    final LocalDate end = toLocalDate(interval
        .getEnd()
        .toDateMidnight());
    final var loggedIn = Startup.getAgent(request);
    if (loggedIn == null) {
      throw new ForbiddenException();
    }
    final var agents = Locator.$$(loggedIn
        .getViewableAgentsQuery(false)
        .and(Agent.isSales.and(Agent.activeAfter(interval.getStart()))));
    final var badOutbounds = new HashMap<String, JsonList>();

    try (DSLContext dsl = using(Locator.pool, SQLDialect.POSTGRES_10)) {
      Table<?> call = table("Call");
      Table<?> segment = table("Segment");
      dsl
          .select(field("call.agent"), field("segment.callerid_number"),
              count(call.asterisk()).as("calls"),
              field("EXTRACT(epoch FROM SUM(call.duration))/60").as("talktime")
          )
          .from(call
              .join(segment)
              .on("call.key=segment.calL"))
          .where(field("call.agent")
              .in(agents
                  .stream()
                  .map(a -> a.key)
                  .collect(toSet()))
              .and(field("call.direction", CD.class)
                  .eq(CD.OUTBOUND)
                  .and(field("call.created", LocalDate.class)
                      .between(start, end))))
          .groupBy(field("call.agent"), field("segment.callerid_number"))
          .having(count(call.asterisk()).gt(5))
          .forEach(row -> {
            final String agent = row.get(field("call.agent"), String.class);
            final String cid = row.get(field("segment.callerid_number"), String.class);
            final int calls = row.get(field("calls"), Integer.class);
            final int talktime = row.get(field("talktime"), Integer.class);
            badOutbounds
                .computeIfAbsent(agent, a -> new JsonList())
                .add(
                    new JsonMap()
                        .$("cid", Address.formatPhoneNumber(cid))
                        .$("calls", calls)
                        .$("talktime",
                            new net.inetalliance.types.Duration(
                                talktime * 1000).getShortString()
                        ));
          });
    }
    final Comparator<Json> calls = Comparator.comparing(j -> ((JsonMap) j).getInteger("calls"));
    final Comparator<Json> reversed = calls.reversed();
    var totals = new HashMap<String, JsonMap>();

    badOutbounds
        .forEach((agent, l) -> {
          l.sort(reversed);

          var total = new JsonMap()
              .$("calls", l.stream()
                  .mapToInt(m -> ((JsonMap) m).getInteger("calls"))
                  .sum());
          if (l.size() > 10) {
            l.subList(10, l.size())
                .clear();
          }
          totals.put(agent, total);
        });

    final JsonList rows = JsonList.collect(badOutbounds.entrySet(), e -> new JsonMap()
        .$("agent", Locator
            .$(new Agent(e.getKey()))
            .getFullName())
        .$("key", e.getKey())
        .$("outbounds", e.getValue())
        .$("total", totals.get(e.getKey())));

    final Comparator<Json> byTotalCalls = Comparator.comparing(r -> {
      var m = (JsonMap) r;
      return totals.get(m.get("key")).getInteger("calls");
    });
    rows.sort(byTotalCalls.reversed());

    respond(response,
        new JsonMap().$("rows", rows)
    );
  }

  enum CD
      implements EnumType {
    OUTBOUND;

    @Override
    public String getLiteral() {
      return "OUTBOUND";
    }

    @Override
    public Schema getSchema() {
      return schema(DSL.name("public"));
    }

    @Override
    public String getName() {
      return "calldirection";
    }
  }
}


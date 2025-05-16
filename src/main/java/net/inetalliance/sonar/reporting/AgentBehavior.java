package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.types.Address;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.jooq.EnumType;
import org.jooq.SQLDialect;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;

import static java.time.format.FormatStyle.SHORT;
import static java.util.stream.Collectors.toSet;
import static org.jooq.impl.DSL.*;

@WebServlet("/reporting/reports/behavior")
public class AgentBehavior
        extends AngularServlet {

    private static LocalDate toLocalDate(final LocalDate midnight) {
        return midnight;
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val interval = Callgrove.getInterval(request);
        val start = toLocalDate(interval.start().toLocalDate());
        val end = toLocalDate(interval.end().toLocalDate());
        val loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        val agents = Locator.$$(loggedIn
                .getViewableAgentsQuery(false)
                .and(Agent.isSales.and(Agent.activeAfter(interval.start()))));
        val badOutbounds = new HashMap<String, JsonList>();

        try (var dsl = using(Locator.jdbc.getDataSource(), SQLDialect.POSTGRES_10)) {
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
                        val agent = row.get(field("call.agent"), String.class);
                        val cid = row.get(field("segment.callerid_number"), String.class);
                        final int calls = row.get(field("calls"), Integer.class);
                        final int talktime = row.get(field("talktime"), Integer.class);
                        badOutbounds
                                .computeIfAbsent(agent, _ -> new JsonList())
                                .add(
                                        new JsonMap()
                                                .$("cid", Address.formatPhoneNumber(cid))
                                                .$("calls", calls)
                                                .$("talktime",
                                                        DateTimeFormats.ofDuration(SHORT).format(Duration.ofSeconds(talktime))
                                                ));
                    });
        }
        final Comparator<Json> calls = Comparator.comparing(j -> ((JsonMap) j).getInteger("calls"));
        val reversed = calls.reversed();
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

        val rows = JsonList.collect(badOutbounds.entrySet(), e -> new JsonMap()
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

        @SuppressWarnings("SpellCheckingInspection")
        @Override
        public String getName() {
            return "calldirection";
        }
    }
}


package net.inetalliance.sonar.reporting;

import com.callgrove.*;
import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.potion.*;
import net.inetalliance.sonar.api.*;
import net.inetalliance.types.json.*;
import org.joda.time.*;
import org.jooq.*;
import org.jooq.impl.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.*;

import static java.util.stream.Collectors.*;
import static org.jooq.impl.DSL.*;

@WebServlet("/reporting/reports/behavior")
public class AgentBehavior
		extends AngularServlet {
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
					.select(field("call.agent"), field("segment.callerid_number"), count(call.asterisk()).as("calls"),
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
				.$("key",e.getKey())
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
}


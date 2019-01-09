package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SalesStage;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.funky.math.Stats;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Duration;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.callgrove.types.CallDirection.OUTBOUND;
import static java.util.stream.Collectors.toCollection;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet(urlPatterns = "/api/webHookStats")
public class WebHookStats
		extends AngularServlet {


	private static class Statistics {
		int queueCalls;
		int outboundCalls;
		int n;
		int attempted;
		int contacted;
		Calculator<Currency> sales;
		Calculator<Long> talkTime;
		Calculator<Long> delay;
		Calculator<Long> contactDelay;

		public Statistics() {
			talkTime = Calculator.newLong();
			delay = Calculator.newLong();
			contactDelay = Calculator.newLong();
			sales = new Calculator<>(Currency.MATH);
		}

		int getCalls() {
			return queueCalls + outboundCalls;
		}

		JsonMap toJson() {
			final Stats<Currency> stats = sales.getStats();
			return new JsonMap()
					.$("queueCalls", queueCalls)
					.$("outboundCalls", outboundCalls)
					.$("n", n)
					.$("sold", stats.n)
					.$("revenue", stats.sum.doubleValue())
					.$("attempted", attempted)
					.$("contacted", contacted)
					.$("talkTime", new Duration(talkTime.getStats().mean).getShortString())
					.$("delay", new Duration(delay.getStats().mean).getShortString())
					.$("contactDelay", new Duration(contactDelay.getStats().mean).getShortString());
		}

		public void add(final Statistics value) {
			queueCalls += value.queueCalls;
			outboundCalls += value.outboundCalls;
			n += value.n;
			attempted += value.attempted;
			contacted += value.contacted;
			talkTime.add(value.talkTime.getStats());
			delay.add(value.delay.getStats());
			contactDelay.add(value.contactDelay.getStats());
			sales.add(value.sales.getStats());
		}
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Query<Opportunity> webhook = Opportunity.hasWebhook;
		final JsonMap json = new JsonMap();
		final DateMidnight today = new DateMidnight();
		json.put("new", count(webhook.and(Opportunity.createdAfter(today))));
		json.put("total", count(webhook));
		json.put("sold", count(webhook.and(Opportunity.isSold)));
		final Set<Integer> unattempted = new HashSet<>();
		final Set<Integer> uncontacted = new HashSet<>();

		final Map<String, Statistics> stats = new LazyMap<String, Statistics>(new HashMap<>(), s->new Statistics());

		final Statistics todayStats = new Statistics();
		forEach(webhook, opportunity -> {
			final Statistics agent = stats.get(opportunity.getAssignedTo().key);
			agent.n++;
			if (opportunity.getStage() == SalesStage.SOLD) {
				agent.sales.accept(opportunity.getAmount());
			}
			final Statistics callStats = new Statistics();
			final DateMidnight created = opportunity.getCreated().toDateMidnight();
			final boolean isToday = opportunity.getCreated().isAfter(today);
			if (isToday) {
				todayStats.n++;
			}
			forEach(Call.withContact(opportunity.getContact()).and(Call.isAfter(created)).orderBy("created", ASCENDING),
				call -> {
					if (agent.delay.k == 0) {
						agent.delay.accept(call.getCreated().getMillis() - opportunity.getCreated().getMillis());
					}
					if (call.getDirection() == OUTBOUND) {
						callStats.outboundCalls++;
					} else {
						callStats.queueCalls++;
					}
					final long talkTime = call.getTalkTime();
					if (talkTime > 60000) {
						callStats.contacted++;
						callStats.talkTime.accept(talkTime);
						if (agent.contactDelay.k == 0) {
							agent.contactDelay.accept(call.getCreated().getMillis() - opportunity.getCreated().getMillis());
						}
					}
				});
			agent.queueCalls += callStats.queueCalls;
			agent.outboundCalls += callStats.outboundCalls;
			if (callStats.contacted > 0) {
				agent.contacted++;
				if (isToday) {
					todayStats.contacted++;
				}
			} else {
				uncontacted.add(opportunity.id);
			}
			if (callStats.getCalls() > 0) {
				agent.attempted++;
				if (isToday) {
					todayStats.attempted++;
				}
			} else {
				unattempted.add(opportunity.id);
			}
			if (callStats.talkTime.k > 0) {
				agent.talkTime.add(callStats.talkTime.getStats());
				if (isToday) {
					todayStats.talkTime.add(callStats.talkTime.getStats());
				}
			}
			if (callStats.delay.k > 0) {
				agent.delay.add(callStats.delay.getStats());
				if (isToday) {
					todayStats.delay.add(callStats.delay.getStats());
				}
			}
			if (callStats.contactDelay.k > 0) {
				agent.contactDelay.add(callStats.contactDelay.getStats());
				if (isToday) {
					todayStats.contactDelay.add(callStats.contactDelay.getStats());
				}
			}
		});
		final Statistics total = new Statistics();
		final JsonMap agents = new JsonMap();
		for (Map.Entry<String, Statistics> entry : stats.entrySet()) {
			final Statistics value = entry.getValue();
			total.add(value);
			final JsonMap agentJson = value.toJson();
			final String key = entry.getKey();
			agentJson.put("name", $(new Agent(key)).getLastNameFirstInitial());
			agents.put(key, agentJson);
		}
		agents.put("Today", todayStats.toJson().$("name", "Today"));
		json.put("agents", agents);
		json.put("total", total.toJson());
		final JsonList notAttempted = unattempted.stream().map(
				id -> toJson(Locator.$(new Opportunity(id)))).collect(toCollection(JsonList::new));
		json.put("unattempted", notAttempted);
		final JsonList notContacted = uncontacted.stream().map(
				id -> toJson(Locator.$(new Opportunity(id)))).collect(toCollection(JsonList::new));
		json.put("uncontacted", notContacted);
		respond(response, json);
	}

	private JsonMap toJson(final Opportunity o) {
		final Contact c = o.getContact();
		return new JsonMap()
				.$("agent", o.getAssignedTo().getLastNameFirstInitial())
				.$("name", c.getFullName())
				.$("phone", c.getShipping().getPhone())
				.$("email", c.getEmail())
				.$("age", new Duration(o.getCreated().toDate(), new DateTime().toDate()).getShortString());

	}
}

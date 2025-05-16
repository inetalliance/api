package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Dates;
import com.ameriglide.phenix.core.Stats;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SalesStage;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;

import static com.callgrove.types.CallDirection.OUTBOUND;
import static java.time.format.FormatStyle.SHORT;
import static java.util.stream.Collectors.toCollection;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/webHookStats")
public class WebHookStats
        extends AngularServlet {

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val webhook = Opportunity.hasWebhook;
        val json = new JsonMap();
        val today = LocalDateTime.now();
        json.put("new", count(webhook.and(Opportunity.createdAfter(today))));
        json.put("total", count(webhook));
        json.put("sold", count(webhook.and(Opportunity.isSold)));
        val unattempted = new HashSet<Integer>();
        val uncontacted = new HashSet<Integer>();

        val stats = new LazyMap<String, Statistics>(new HashMap<>(),
                s -> new Statistics());

        val todayStats = new Statistics();
        forEach(webhook, opportunity -> {
            val agent = stats.get(opportunity.getAssignedTo().key);
            agent.n++;
            if (opportunity.getStage() == SalesStage.SOLD) {
                agent.sales.accept(opportunity.getAmount());
            }
            val callStats = new Statistics();
            var created = opportunity.getCreated().toLocalDate();
            val isToday = opportunity.getCreated().isAfter(today);
            if (isToday) {
                todayStats.n++;
            }
            forEach(Call.withContact(opportunity.getContact()).and(Call.isAfter(created))
                            .orderBy("created", ASCENDING),
                    call -> {
                        if (agent.delay.k == 0) {
                            agent.delay
                                    .accept(Dates.toEpochMilli(call.getCreated()) - Dates.toEpochMilli(opportunity.getCreated()));
                        }
                        if (call.getDirection() == OUTBOUND) {
                            callStats.outboundCalls++;
                        } else {
                            callStats.queueCalls++;
                        }
                        val talkTime = call.getTalkTime();
                        if (talkTime > 60000) {
                            callStats.contacted++;
                            callStats.talkTime.accept(talkTime);
                            if (agent.contactDelay.k == 0) {
                                agent.contactDelay
                                        .accept(Dates.toEpochMilli(call.getCreated()) - Dates.toEpochMilli(opportunity.getCreated()));
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
        val total = new Statistics();
        val agents = new JsonMap();
        for (var entry : stats.entrySet()) {
            val value = entry.getValue();
            total.add(value);
            val agentJson = value.toJson();
            val key = entry.getKey();
            agentJson.put("name", $(new Agent(key)).getLastNameFirstInitial());
            agents.put(key, agentJson);
        }
        agents.put("Today", todayStats.toJson().$("name", "Today"));
        json.put("agents", agents);
        json.put("total", total.toJson());
        val notAttempted =
                unattempted.stream().map(id -> toJson(Locator.$(new Opportunity(id))))
                        .collect(toCollection(JsonList::new));
        json.put("unattempted", notAttempted);
        val notContacted =
                uncontacted.stream().map(id -> toJson(Locator.$(new Opportunity(id))))
                        .collect(toCollection(JsonList::new));
        json.put("uncontacted", notContacted);
        respond(response, json);
    }

    private JsonMap toJson(final Opportunity o) {
        val c = o.getContact();
        return new JsonMap().$("agent", o.getAssignedTo().getLastNameFirstInitial())
                .$("name", c.getFullName())
                .$("phone", c.getShipping().getPhone())
                .$("email", c.getEmail())
                .$("age", DateTimeFormats.ofDuration(SHORT).format(Duration.between(o.getCreated(), LocalDateTime.now())));

    }

    private static class Statistics {

        int queueCalls;
        int outboundCalls;
        int n;
        int attempted;
        int contacted;
        final Calculator<Currency> sales;
        final Calculator<Long> talkTime;
        final Calculator<Long> delay;
        final Calculator<Long> contactDelay;

        public Statistics() {
            talkTime = Calculator.newLong();
            delay = Calculator.newLong();
            contactDelay = Calculator.newLong();
            sales = new Calculator<>(Currency.math);
        }

        int getCalls() {
            return queueCalls + outboundCalls;
        }

        JsonMap toJson() {
            final Stats<Currency> stats = sales.getStats();
            final var durationFormatter = DateTimeFormats.ofDuration(SHORT);
            return new JsonMap().$("queueCalls", queueCalls)
                    .$("outboundCalls", outboundCalls)
                    .$("n", n)
                    .$("sold", stats.n)
                    .$("revenue", stats.sum.doubleValue())
                    .$("attempted", attempted)
                    .$("contacted", contacted)
                    .$("talkTime", durationFormatter.format(Duration.ofMillis(talkTime.getStats().mean)))
                    .$("delay", durationFormatter.format(Duration.ofMillis(delay.getStats().mean)))
                    .$("contactDelay", durationFormatter.format(Duration.ofMillis(contactDelay.getStats().mean)));
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
}

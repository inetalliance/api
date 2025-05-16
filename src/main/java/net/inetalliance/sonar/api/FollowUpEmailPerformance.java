package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Enums;
import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.getReportingInterval;
import static java.time.format.FormatStyle.SHORT;
import static net.inetalliance.potion.Locator.$$;

@WebServlet("/api/followUpEmailPerformance")
public class FollowUpEmailPerformance
        extends AngularServlet {

    private final Info<ProductLine> productLineInfo = Info.$(ProductLine.class);
    private final Info<Site> siteInfo = Info.$(Site.class);

    private static String formatDuration(final Number number) {
        val longValue = number.longValue();
        return longValue == 0L ? "" : DateTimeFormats.ofDuration(SHORT).format(Duration.ofMillis(longValue));
    }

    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val interval = getReportingInterval(request);
        val productLine = productLineInfo.lookup(request.getParameter("productLine"));
        if (productLine == null) {
            throw new BadRequestException("Invalid product line specified");
        }
        val site = siteInfo.lookup(request.getParameter("site"));
        if (site == null) {
            throw new BadRequestException("Invalid site specified");
        }
        val sourceParam = request.getParameter("source");
        val source = isEmpty(sourceParam) || "all".equals(sourceParam)
                ? null
                : Enums.decamel(SaleSource.class, sourceParam);

        var oppQuery = Opportunity.withSite(site)
                .and(Opportunity.withProductLine(productLine));
        if (source != null) {
            oppQuery = oppQuery.and(Opportunity.withSaleSource(source));
        }
        val query = SentFollowUpEmail.hasEmail
                .and(SentFollowUpEmail.withSentIn(interval))
                .and(SentFollowUpEmail.opportunityJoin(oppQuery));
        final Map<FollowUpEmail, Row> rows = new HashMap<>();
        for (val email : $$(query)) {
            rows.computeIfAbsent(email.getEmail(), Row::new).accept(email);
        }
        final List<Row> data = new ArrayList<>(rows.values());
        Collections.sort(data);
        respond(response, JsonList.collect(data, Row::toJson));
    }

    static final class Row
            implements Consumer<SentFollowUpEmail>, Comparable<Row> {

        private final int id;
        private final String name;
        private final LocalDateTime lastSent;
        private final Calculator<Long> timeToOpen;
        private final Calculator<Long> timeToConvert;
        private int sent;
        private int opened;
        private int conversions;

        private Row(final FollowUpEmail email) {
            id = email.id;
            name = email.getName();
            lastSent = email.getLastSent();
            sent = 0;
            opened = 0;
            conversions = 0;
            timeToOpen = Calculator.newLong();
            timeToConvert = Calculator.newLong();
        }

        private static JsonMap toJson(final Row row) {
            val timeToOpenStats = row.timeToOpen.getStats();
            val timeToConvertStats = row.timeToConvert.getStats();
            return new JsonMap().$("id", row.id)
                    .$("name", row.name)
                    .$("lastSent", DateTimeFormats.ofDateTime(SHORT).format(row.lastSent))
                    .$("sent", row.sent)
                    .$("opened", row.opened)
                    .$("conversions", row.conversions)
                    .$("timeToOpen", new JsonMap().$("mean", formatDuration(timeToOpenStats.mean))
                            .$("stdDeviation",
                                    formatDuration(timeToOpenStats.stdDeviation)))
                    .$("timeToConvert", new JsonMap().$("mean", formatDuration(timeToConvertStats.mean))
                            .$("stdDeviation",
                                    formatDuration(timeToConvertStats.stdDeviation)));
        }

        @Override
        public int compareTo(final Row that) {
            return Integer.compare(that.id, this.id);
        }

        @Override
        public void accept(final SentFollowUpEmail email) {
            sent++;
            if (email.isOpened()) {
                opened++;
                timeToOpen.accept(ChronoUnit.MILLIS.between(email.getSent(), email.getOpened()));
            }
            if (email.isOpened() && email.isConverted()) {
                conversions++;
                timeToConvert.accept(ChronoUnit.MILLIS.between(email.getOpened(), email.getConversion()));
            }
        }
    }
}

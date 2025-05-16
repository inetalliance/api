package net.inetalliance.sonar.util;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.Stats;
import com.callgrove.Callgrove;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import lombok.val;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.annotations.Parameter;
import net.inetalliance.types.annotations.Required;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;

import static com.callgrove.obj.Opportunity.soldInInterval;
import static com.callgrove.obj.Opportunity.withProductLine;
import static net.inetalliance.potion.Locator.forEach;

public class ValueAnalyzer extends DbCli {

    @Parameter('p')
    @Required
    Integer productLineId;

    @Override
    protected void exec() {
        Callgrove.register();

        val productLine = Locator.$(new ProductLine(productLineId));
        if (productLine == null) {
            throw new IllegalArgumentException("Could not find product line " + productLineId);
        }

        val now = LocalDate.now();
        val start = now.minusYears(2);
        System.out.printf("2 year stats for %s (%s - %s)%n", productLine.getName(), start, now);
        val q = withProductLine(productLine)
                .and(Opportunity.withAmountGreaterThan(Currency.ZERO))
                .and(soldInInterval(new DateTimeInterval(start, now)));

        val n = Locator.count(q);
        var m = new ProgressMeter(n);
        var allCalc = new Calculator<>(Currency.math);

        var allMeter = allCalc.andThen(_ -> m.increment());
        forEach(q, o -> allMeter.accept(o.getAmount()));

        val allStats = allCalc.getStats();
        System.out.println(allStats);
        var max = allStats.mean() + 2 * allStats.stdDeviation;
        var min = allStats.mean() - 2 * allStats.stdDeviation;
        System.out.printf("excluding μ ± 2σ: %s to %s%n", new Currency(min), new Currency(max));

        var calc = new Calculator<>(Currency.math);
        m.reset(n);
        forEach(q, o -> {
            m.increment();
            val amount = o.getAmount().doubleValue();
            if (min < amount && max > amount) {
                calc.accept(o.getAmount());
            }
        });
        final Stats<Currency> stats = calc.getStats();
        System.out.println(stats);
        System.exit(0);

    }

    public static void main(String[] args) {
        Cli.run(new ValueAnalyzer(), args);
    }
}

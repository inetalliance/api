package net.inetalliance.sonar.util;

import static com.callgrove.obj.Opportunity.soldInInterval;
import static com.callgrove.obj.Opportunity.withProductLine;
import static net.inetalliance.potion.Locator.forEach;

import com.callgrove.Callgrove;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import net.inetalliance.cli.Cli;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.funky.math.Stats;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.annotations.Parameter;
import net.inetalliance.types.annotations.Required;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

public class ValueAnalyzer extends DbCli {

  @Parameter('p')
  @Required
  Integer productLineId;

  @Override
  protected void exec() {
    Callgrove.register();

    final ProductLine productLine = Locator.$(new ProductLine(productLineId));
    if (productLine == null) {
      throw new IllegalArgumentException("Could not find product line " + productLineId);
    }

    final DateMidnight now = new DateMidnight();
    final DateMidnight start = now.minusYears(2);
    System.out.printf("2 year stats for %s (%s - %s)%n", productLine.getName(), start, now);
    final Query<Opportunity> q = withProductLine(productLine)
        .and(Opportunity.withAmountGreaterThan(Currency.ZERO))
        .and(soldInInterval(new Interval(start, now)));

    final int n = Locator.count(q);
    ProgressMeter m = new ProgressMeter(n);
    var allCalc = new Calculator<>(Currency.MATH);

    var allMeter = allCalc.andThen(c -> m.increment());
    forEach(q, o -> allMeter.accept(o.getAmount()));

    final Stats<Currency> allStats = allCalc.getStats();
    System.out.println(allStats);
    double max = allStats.mean() + 2 * allStats.stdDeviation;
    double min = allStats.mean() - 2 * allStats.stdDeviation;
    System.out.printf("excluding μ ± 2σ: %s to %s%n", new Currency(min), new Currency(max));

    var calc = new Calculator<>(Currency.MATH);
    m.reset(n);
    forEach(q, o -> {
      m.increment();
      final double amount = o.getAmount().doubleValue();
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

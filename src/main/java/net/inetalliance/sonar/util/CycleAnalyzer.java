package net.inetalliance.sonar.util;

import static com.callgrove.obj.Opportunity.withProductLine;
import static com.callgrove.obj.Opportunity.withSite;
import static java.util.stream.Collectors.toSet;

import com.callgrove.Callgrove;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.inetalliance.cli.Cli;
import net.inetalliance.funky.Funky;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.types.annotations.Parameter;
import net.inetalliance.types.annotations.Required;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.base.BaseDateTime;

public class CycleAnalyzer extends DbCli {


  @Parameter('p')
  @Required
  Integer productLineId;
  @Parameter('s')
  @Required
  Integer siteId;

  @Override
  protected void exec() {

    Callgrove.register();

    final ProductLine productLine = Locator.$(new ProductLine(productLineId));

    if (productLine == null) {
      throw new IllegalArgumentException("Could not find product line with id " + productLineId);
    }
    final Site site = Locator.$(new Site(siteId));
    if (site == null) {
      throw new IllegalArgumentException("Could not find site with id " + siteId);
    }

    final Query<Opportunity> q = withSite(site)
        .and(withProductLine(productLine))
        .and(Opportunity.isSold)
        .orderBy("created");

    System.err.println("Site is " + site.getName());
    System.err.println("Product line is " + productLine.getName());
    final int count = Locator.count(q);
    System.err.println("Processing " + count + " opps");
    AtomicInteger broken = new AtomicInteger(0);

    Locator.forEach(q, o -> {
      Interval interval = getInterval(o);
      if (interval == null) {
        broken.getAndIncrement();
      } else {
        System.out.printf("%d\t%s\t%s\t%d%n", o.id, interval.getStart(), interval.getEnd(),
            interval.toDuration().getStandardDays());
      }
    });
    System.err.println("There were " + broken.get() + " broken opps");


  }

  private static Interval getInterval(final Opportunity opp) {

    System.err.println("Opp " + opp.id);
    if(opp.getContact().getAllPhoneNumbers().findFirst().isPresent()) {
      final Set<Contact> contacts = Locator.$$(Contact.withPhoneNumberIn(opp.getContact()));
      System.err.println("# contacts " + contacts.size());
      if(contacts.size() > 5) {
        return null;
      }

      Set<String> allNumbers = contacts.stream()
          .map(Contact::getAllPhoneNumbers)
          .map(s -> s.collect(toSet()))
          .flatMap(Funky::stream)
          .limit(5)
          .collect(toSet());
      System.err.println("all phone numbers " + allNumbers);

      final Set<Opportunity> opps = contacts.stream()
          .map(c -> Locator.$$(Opportunity.isSold.and(Opportunity.withContact(c))))
          .flatMap(Funky::stream).collect(toSet());

      System.err.println("# opps " + opps.size());

      DateTime earliestCallDate = new DateTime(opps.stream()
          .map(Opportunity::getSaleDate)
          .map(d -> d.plusWeeks(2))
          .filter(d -> d.isBefore(opp.getSaleDate()))
          .mapToLong(BaseDateTime::getMillis)
          .max()
          .orElse(opp.getCreated().minusMonths(2).getMillis()));
      if (earliestCallDate.isAfter(opp.getCreated())) {
        earliestCallDate = opp.getCreated().minusMonths(2);
      }

      System.err.println("sale date " + opp.getSaleDate());
      System.err.println("creation date " + opp.getCreated());
      System.err.println("earliest acceptable call date " + earliestCallDate);

      final DateTime earliestCall = Locator.$$(Call.withCallerIdIn(allNumbers)
              .and(Call.inInterval(new Interval(earliestCallDate, opp.getCreated()))),
          Aggregate.MIN, DateTime.class, "created");
      System.err.println("earliest call " + earliestCall);

      final DateTime firstContact = earliestCall == null ? opp.getCreated() : earliestCall;
      if(opp.getSaleDate().isBefore(firstContact)) {
        return null;
      }
      return new Interval(firstContact, opp.getSaleDate());
    }
    return null;

  }

  public static void main(String[] args) {
    Cli.run(new CycleAnalyzer(), args);
  }
}

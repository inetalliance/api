package net.inetalliance.sonar;

import static java.util.stream.Collectors.toList;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Event;
import com.callgrove.types.EventType;
import com.github.seratch.jslack.Slack;
import com.github.seratch.jslack.api.model.Attachment;
import com.github.seratch.jslack.api.model.Field;
import com.github.seratch.jslack.api.webhook.Payload;
import java.io.IOException;
import java.text.ChoiceFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.inetalliance.cli.Cli;
import net.inetalliance.cron.CronJob;
import net.inetalliance.cron.CronStatus;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.potion.DbCli;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

public class WorkStatusNotifier implements CronJob {

  @Override
  public String getName() {
    return "Work Status Notifier";
  }

  private final Slack slack = Slack.getInstance();
  private final String hook = "https://hooks.slack.com/services/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";

  private static final PeriodFormatter durationFmt = new PeriodFormatterBuilder().appendHours()
      .appendSuffix("h").appendSeparator(" ").appendMinutes().appendSuffix("m").toFormatter();

  private static class WorkRecord {

    final String name;

    public WorkRecord(Agent agent) {
      this.name = agent.getFullName();
    }

    int activeSegments;
    long activeMs;
    int forwardedSegments;
    long forwardedMs;
    boolean loggedOn;
    boolean forwarded;
    boolean registered;
    long activeStart;
    long forwardedStart;
    long firstLogon;
    long lastLogoff;

    public Field toSlack() {
      return Field.builder().title(name).value(toString()).build();

    }

    public String toString() {
      var blocks = new ChoiceFormat("1#block|1<blocks");
      var s = "Start: " + DateTimeFormat.shortTime().print(new DateTime(firstLogon)) + ", ";
      if (activeSegments > 0) {
        final var activeTime = new org.joda.time.Duration(activeMs);
        s += "at desk: " + activeSegments + " work " + blocks.format(activeSegments) + ", "
            + durationFmt.print(activeTime.toPeriod());
      }
      if (forwardedSegments > 0) {
        if (activeSegments > 0) {
          s += ", ";
        }
        final var mobileTime = new org.joda.time.Duration(forwardedMs);
        s += "on mobile: " + forwardedSegments + " work " + blocks.format(forwardedSegments) + ", "
            + durationFmt.print(mobileTime.toPeriod());
      }
      s += ", Leave: " + DateTimeFormat.shortTime().print(new DateTime(lastLogoff)) + " ";

      return s;

    }

    public boolean hasWork() {
      return activeSegments > 0 || forwardedSegments > 0;
    }
  }

  @Override
  public void exec(CronStatus status) {
    final var records = new HashMap<Agent, WorkRecord>();
    final var yesterday = new DateMidnight().minusDays(1).toInterval();

    forEach(Agent.isActive.and(Agent.isSales), agent -> {
      log.info("Setting up %s", agent.getFullName());
      var r = records.computeIfAbsent(agent, a -> new WorkRecord(a));
      r.loggedOn = agent.getLastLogonChange(yesterday.getStart()) == EventType.LOGON;
      r.forwarded = agent.getLastForwardChange(yesterday.getStart()) == EventType.FORWARD;
      r.registered = agent.getLastRegistrationChange(yesterday.getStart()) == EventType.REGISTER;
      if (r.loggedOn && r.registered && !r.forwarded) {
        r.activeStart = yesterday.getStartMillis();
      }
      if (r.loggedOn && r.forwarded) {
        r.forwardedStart = yesterday.getStartMillis();
      }
    });
    log.info("Processing yesterdays' events");
    forEach(Event.inInterval(yesterday).orderBy("date"), e -> {
      final var agent = e.getAgent();
      if (records.containsKey(agent)) {
        var r = records.get(agent);
        log.info(e);
        switch (e.getEventType()) {
          case LOGON:
            if (!r.loggedOn) {
              if (r.forwarded) {
                r.forwardedStart = e.getDate().getMillis();
                if(r.firstLogon == 0) {
                  r.firstLogon = e.getDate().getMillis();
                }
              } else if (r.registered) {
                r.activeStart = e.getDate().getMillis();
                if(r.firstLogon == 0) {
                  r.firstLogon = e.getDate().getMillis();
                }
              }
            }
            r.loggedOn = true;
            break;
          case LOGOFF:
            if (r.forwarded) {
              r.forwardedSegments++;
              r.forwardedMs += e.getDate().getMillis() - r.forwardedStart;
            } else if (r.registered) {
              r.activeSegments++;
              r.activeMs += e.getDate().getMillis() - r.activeStart;
            }
            r.loggedOn = false;
            r.lastLogoff = e.getDate().getMillis();
            break;
          case FORWARD:
            if (!r.forwarded) {
              if (r.loggedOn && r.registered) {
                if(r.firstLogon == 0) {
                  r.firstLogon = e.getDate().getMillis();
                }
                r.forwardedStart = e.getDate().getMillis();
                r.activeSegments++;
                r.activeMs += e.getDate().getMillis() - r.activeStart;
              }
              r.forwarded = true;
            }
            break;
          case UNFORWARD:
            if (r.forwarded && r.loggedOn) {
              r.forwardedSegments++;
              r.forwardedMs += e.getDate().getMillis() - r.forwardedStart;
              if (r.registered) {
                r.activeStart = e.getDate().getMillis();
                if(r.firstLogon == 0) {
                  r.firstLogon = e.getDate().getMillis();
                }
              }
            }
            r.forwarded = false;
            break;
          case UNREGISTER:
            if (r.registered) {
              if (r.loggedOn && !r.forwarded) {
                r.activeSegments++;
                r.activeMs += e.getDate().getMillis() - r.activeStart;
              }
              if(!r.forwarded) {
                r.lastLogoff = e.getDate().getMillis();
              }
            }
            r.registered = false;
            break;
          case REGISTER:
            if (!r.registered) {
              if (r.loggedOn && !r.forwarded) {
                r.activeStart = e.getDate().getMillis();
                if(r.firstLogon == 0) {
                  r.firstLogon = e.getDate().getMillis();
                }
              }
            }
            r.registered = true;
            break;
        }
      }


    });
    Set.of("7220", "7501", "7108").stream()
        .map(key -> $(new Agent(key))).forEach(manager -> {
      var fields = Funky.stream(manager.getManagedCallCenters(null))
          .map(c -> $$(Agent.withCallCenter(c))).flatMap(Funky::stream)
          .sorted(Comparator.comparing(Agent::getFullName)).map(records::get)
          .filter(Objects::nonNull)
          .filter(WorkRecord::hasWork)
          .map(WorkRecord::toSlack).collect(toList());

      try {
        var mgr = List.of(
            Field.builder().title("Work Status For")
                .value(DateTimeFormat.shortDate().print(new DateMidnight().minusDays(1))).build(),
            Field.builder().title("Manager").value(manager.getFullName()).build()

        );
        slack.send(hook, Payload
            .builder()
            .attachments(List.of(Attachment.builder().fields(mgr).build(),
                Attachment.builder().fields(fields).build()))
            .build());
      } catch (IOException e) {
        log.error(e);
      }
    });
    System.exit(0);


  }

  public static void main(String[] args) {
    Cli.run(new DbCli() {
      @Override
      protected void exec() {
        Callgrove.register();
        new WorkStatusNotifier().exec(null);
      }
    }, args);
  }

  private static final transient Log log = Log.getInstance(WorkStatusNotifier.class);
}

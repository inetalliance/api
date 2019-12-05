package net.inetalliance.sonar;

import static com.callgrove.types.CallDirection.QUEUE;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.forEach;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Segment;
import com.callgrove.types.EventType;
import com.github.seratch.jslack.Slack;
import java.util.stream.Stream;
import net.inetalliance.cli.Cli;
import net.inetalliance.cron.CronJob;
import net.inetalliance.cron.CronStatus;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.sql.OrderBy.Direction;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

public class WorkStatusNotifier implements CronJob {

  @Override
  public String getName() {
    return "Work Status Notifier";
  }

  private final Slack slack = Slack.getInstance();
  private final String hook = "https://hooks.slack.com/services/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";


  @Override
  public void exec(CronStatus status) {
    final var yesterday = new DateMidnight().minusDays(0).toInterval();
    var callCenters = Stream.of("7108", "7220", "7501")
        .map(Agent::new)
        .map(Locator::$)
        .map(a -> a.getManagedCallCenters(null))
        .flatMap(Funky::stream)
        .collect(toSet());

    forEach(Agent.isActive.and(Agent.isSales).and(Agent.withCallCenters(callCenters)), agent -> {
      log.info("Looking for missed segments for %s", agent.getFullName());
      forEach(Segment.isAnswered.negate().and(Segment.inInterval(yesterday)
          .and(Segment.withAgent(agent))), segment -> {
        if (segment.call.getDirection() == QUEUE) {
          final DateTime missedTime = segment.getCreated();
          var mobile = agent.getLastForwardChange(missedTime) == EventType.FORWARD;
          var registered = agent.getLastRegistrationChange(missedTime) == EventType.REGISTER;
          var orange =
              agent.getLastLogonChange(missedTime) == EventType.LOGON && (mobile || registered);

          if (orange) {
            var callStart = segment.call.getCreated();
            var lastSegment = Locator
                .$1(Segment.withCall(segment.call,Direction.DESCENDING));
            var callTime = new Interval(callStart, lastSegment.getCreated()).toDuration()
                .getStandardSeconds();
            var segmentTime = new Interval(segment.getCreated(), lastSegment.getCreated()).toDuration()
                .getStandardSeconds();
            if (callTime > 15) {
              if (mobile) {
                log.info("[%s] missed a mobile call at %s", segment.call.key,
                    DateTimeFormat.shortTime().print(missedTime));
              } else if(false) {
                log.info("[%s] missed a desk call at %s", segment.call.key,
                    DateTimeFormat.shortTime().print(missedTime));

              }
            }
          }
        }
      });

    });

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

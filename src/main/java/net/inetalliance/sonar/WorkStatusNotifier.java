package net.inetalliance.sonar;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Log;
import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Segment;
import com.callgrove.types.EventType;
import lombok.val;
import net.inetalliance.cli.Cli;
import net.inetalliance.cron.CronJob;
import net.inetalliance.cron.CronStatus;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.sql.OrderBy.Direction;

import java.time.LocalDate;
import java.util.stream.Stream;

import static com.callgrove.types.CallDirection.QUEUE;
import static java.time.format.FormatStyle.SHORT;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.forEach;

public class WorkStatusNotifier implements CronJob {

    @Override
    public String getName() {
        return "Work Status Notifier";
    }


    @Override
    public void exec(CronStatus status) {
        val yesterday = new DateTimeInterval(LocalDate.now().minusDays(0));
        var callCenters = Stream.of("7108", "7220", "7501")
                .map(Agent::new)
                .map(Locator::$)
                .map(a -> a.getManagedCallCenters(null))
                .flatMap(Iterables::stream)
                .collect(toSet());

        forEach(Agent.isActive.and(Agent.isSales).and(Agent.withCallCenters(callCenters)), agent -> {
            log.info("Looking for missed segments for %s", agent.getFullName());
            forEach(Segment.isAnswered.negate().and(Segment.inInterval(yesterday)
                    .and(Segment.withAgent(agent))), segment -> {
                if (segment.call.getDirection() == QUEUE) {
                    val missedTime = segment.getCreated();
                    var mobile = agent.getLastForwardChange(missedTime) == EventType.FORWARD;
                    var registered = agent.getLastRegistrationChange(missedTime) == EventType.REGISTER;
                    var orange =
                            agent.getLastLogonChange(missedTime) == EventType.LOGON && (mobile || registered);

                    if (orange) {
                        var callStart = segment.call.getCreated();
                        var lastSegment = Locator
                                .$1(Segment.withCall(segment.call, Direction.DESCENDING));
                        var callTime = new DateTimeInterval(callStart, lastSegment.getCreated()).toDuration()
                                .toSeconds();
                        if (callTime > 15) {
                            if (mobile) {
                                log.info("[%s] missed a mobile call at %s", segment.call.key,
                                        DateTimeFormats.ofTime(SHORT).format(missedTime));
                            } else {
                                log.info("[%s] missed a desk call at %s", segment.call.key,
                                        DateTimeFormats.ofTime(SHORT).format(missedTime));

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

    private static final Log log = new Log();
}

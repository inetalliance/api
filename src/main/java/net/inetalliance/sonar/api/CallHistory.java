package net.inetalliance.sonar.api;

import static com.callgrove.types.CallDirection.OUTBOUND;
import static java.util.regex.Pattern.compile;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Segment;
import java.util.regex.Matcher;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.Model;
import net.inetalliance.angular.exception.MethodNotAllowedException;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.SortedQuery;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

@WebServlet("/api/callHistory/*")
public class CallHistory
    extends Model<Opportunity, Key<Opportunity>> {

  public static final PeriodFormatter periodFormatter = new PeriodFormatterBuilder().appendHours()
      .appendSuffix("h")
      .appendSeparator(" ")
      .appendMinutes()
      .appendSuffix("m")
      .appendSeparator(" ")
      .appendSeconds()
      .appendSuffix("s")
      .toFormatter();

  public CallHistory() {
    super(compile("/api/callHistory/(.*)"));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response) {
    throw new MethodNotAllowedException();
  }

  @Override
  protected void delete(final HttpServletRequest request, final HttpServletResponse response) {
    throw new MethodNotAllowedException();
  }

  @Override
  protected Key<Opportunity> getKey(final Matcher m) {
    return Key.$(Opportunity.class, m.group(1));
  }

  @Override
  protected void put(final HttpServletRequest request, final HttpServletResponse response) {
    throw new MethodNotAllowedException();
  }

  @Override
  protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity,
      final HttpServletRequest request) {
    final Contact contact = opportunity.getContact();
    final SortedQuery<Call> callQuery = Call.withContact(contact).orderBy("created", DESCENDING);
    final JsonList calls = new JsonList(count(callQuery));
    forEach(callQuery, call -> {
      final JsonMap callMap = new JsonMap();
      calls.add(callMap);
      callMap.$("key").$("notes").$("resolution").$("created").$("direction");
      Info.$(call).fill(call, callMap);
      callMap.$("site", call.getSite() != null
          ? new JsonMap().$("name", call.getSite().getName())
          .$("abbreviation", call.getSite().getAbbreviation())
          : null);
      callMap.put(call.getDirection() == OUTBOUND ? "to" : "from",
          call.getRemoteCallerId().getNumber());
      if (call.getAgent() != null) {
        callMap.put(call.getDirection() == OUTBOUND ? "from" : "to",
            call.getAgent().getLastNameFirstInitial());
      }
      final JsonList talkList = new JsonList();
      callMap.put("talkTime", talkList);
      forEach(Segment.withCall(call).orderBy("created", ASCENDING), segment -> {
        final JsonMap talkMap = new JsonMap();
        if (segment.getAgent() != null) {
          talkMap.put("agent", segment.getAgent().getLastNameFirstInitial());
        }
        if (segment.getAnswered() != null && segment.getTalkTime() != null) {
          talkMap.put("talkTime", periodFormatter.print(segment.getTalkTime().toPeriod()));
        }
        talkList.add(talkMap);
      });
    });
    return calls;
  }
}

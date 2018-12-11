package net.inetalliance.sonar;

import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Segment;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.Model;
import net.inetalliance.angular.exception.MethodNotAllowedException;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.SortedQuery;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;

import static com.callgrove.types.CallDirection.OUTBOUND;
import static java.util.regex.Pattern.compile;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

@WebServlet("/api/callHistory/*")
public class CallHistory
		extends Model<Opportunity, Key<Opportunity>> {
	public static final PeriodFormatter periodFormatter = new PeriodFormatterBuilder()
			.appendHours()
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
	protected void post(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		throw new MethodNotAllowedException();
	}

	@Override
	protected void delete(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		throw new MethodNotAllowedException();
	}

	@Override
	protected Key<Opportunity> getKey(final Matcher m) {
		return Key.$(Opportunity.class, m.group(1));
	}

	@Override
	protected void put(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		throw new MethodNotAllowedException();
	}

	@Override
	protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity, final HttpServletRequest request) {
		final Contact contact = opportunity.getContact();
		final SortedQuery<Call> callQuery = Call.Q.withContact(contact).orderBy("created", DESCENDING);
		final JsonList calls = new JsonList(count(callQuery));
		forEach(callQuery, new P1<Call>() {
			@Override
			public void $(final Call call) {
				final JsonMap callMap = new JsonMap();
				calls.add(callMap);
				callMap
						.$("key")
						.$("notes")
						.$("resolution")
						.$("created")
						.$("direction");
				Info.$(call).fill(call, callMap);
				callMap.put(call.getDirection() == OUTBOUND ? "to" : "from", call.getRemoteCallerId().getNumber());
				if (call.getAgent() != null) {
					callMap.put(call.getDirection() == OUTBOUND ? "from" : "to", call.getAgent().getLastNameFirstInitial());
				}
				final JsonList talkList = new JsonList();
				callMap.put("talkTime", talkList);
				forEach(Segment.Q.withCall(call).orderBy("created", ASCENDING), new P1<Segment>() {
					@Override
					public void $(final Segment arg) {
						final JsonMap talkMap = new JsonMap();
						if (arg.getAgent() != null) {
							talkMap.put("agent", arg.getAgent().getLastNameFirstInitial());
						}
						if (arg.getAnswered() != null && arg.getTalkTime() != null) {
							talkMap.put("talkTime", periodFormatter.print(arg.getTalkTime().toPeriod()));
						}
						talkList.add(talkMap);
					}
				});
			}
		});
		return calls;
	}
}

package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;

import javax.servlet.annotation.WebServlet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.callgrove.obj.Agent.Q.locked;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.potion.Locator.forEach;

@WebServlet("/api/hud")
public class Hud
		extends JsonCronServlet {

	private static final Pattern phone = Pattern.compile("7[0-9][0-9][0-9]");
	private static final F1<CallCenter, Integer> classify = new F1<CallCenter, Integer>() {
		@Override
		public Integer $(final CallCenter callCenter) {
			if (callCenter.getName().startsWith("AZ")) {
				return 1;
			} else if (callCenter.getName().startsWith("GA")) {
				return 0;
			} else if (callCenter.getName().startsWith("NC")) {
				return 2;
			}

			switch (callCenter.id) {
				case 10:
				case 4:
				case 10008:
				case 10012:
				case 10027:
				case 10022:
					return 3;
				case 1:
					return 5;
				case 6:
					return 4;
				case 10025:
					return 7;
				case 7:
					return 8;
				case 10028:
				case 10024:
					return 9;
			}
			return null;

		}
	}.memoize();
	private static String[] callCenters =
			{"Atlanta", "Phoenix", "Raleigh", "AmeriGlide", "Accounting", "Technology", "Affiliates", "Elevators",
			"Delstal", "ATC"};

	public Hud() {
		super(5, MINUTES);
	}

	@Override
	protected Json produce() {
		final Map<Integer, JsonList> agents = new LazyMap<Integer, JsonList>(new TreeMap<>()) {
			@Override
			public JsonList create(final Integer key) {
				return new JsonList(8);
			}
		};
		final Set<String> firstNames = new HashSet<>(8);
		forEach(locked.negate(), new P1<Agent>() {
			@Override
			public void $(final Agent agent) {
				if (phone.matcher(agent.key).matches()) {
					final CallCenter callCenter = agent.getCallCenter();
					final Integer key = classify.$(callCenter);
					if (key != null) {
						final String agentName;
						if (key == 6) {
							switch (callCenter.id) {
								case 10:
									agentName = format("4Med %s", agent.getFirstName());
									break;
								case 4:
									agentName = format("101 %s", agent.getFirstName());
									break;
								case 7:
									agentName = format("A1 %s", agent.getFirstName());
									break;
								default:
									agentName = agent.getFirstName();

							}
						} else {
							if (firstNames.contains(agent.getFirstName())) {
								agentName = agent.getFirstName() + ' ' + agent.getLastName().charAt(0);
							} else {
								agentName = agent.getFirstName();
							}
							firstNames.add(agentName);
						}
						agents.get(key).add(
								new JsonMap()
										.$("name", agentName)
										.$("key", agent.key)
						);
					}

				}
			}
		});
		final JsonList json = new JsonList(callCenters.length);
		for (int i = 0; i < callCenters.length; i++) {
			json.add(new JsonMap()
					.$("name", callCenters[i])
					.$("agents", agents.get(i)));

		}
		return json;
	}

}

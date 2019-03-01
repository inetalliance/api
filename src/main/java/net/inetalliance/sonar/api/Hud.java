package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import net.inetalliance.sonar.*;
import net.inetalliance.types.json.*;
import net.inetalliance.types.struct.maps.*;

import javax.servlet.annotation.*;
import java.util.*;
import java.util.regex.*;

import static com.callgrove.obj.Agent.*;
import static java.lang.String.*;
import static java.util.concurrent.TimeUnit.*;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/hud")
public class Hud
		extends JsonCronServlet {

	private static final Pattern phone = Pattern.compile("7[0-9][0-9][0-9]");

	private static final Map<CallCenter, Integer> cache = new HashMap<>();
	private static String[] callCenters =
			{"Atlanta", "Phoenix", "Raleigh", "AmeriGlide", "Accounting", "Technology", "Affiliates", "Elevators", "Delstal",
			 "ATC"};

	public Hud() {
		super(5, MINUTES);
	}

	@Override
	protected Json produce() {
		final Map<Integer, JsonList> agents = new LazyMap<>(new HashMap<>(), k -> new JsonList(8));
		final Set<String> firstNames = new HashSet<>(8);
		forEach(isLocked.negate(), agent -> {
			if (phone.matcher(agent.key).matches()) {
				final CallCenter callCenter = agent.getCallCenter();
				final Integer key = classify(callCenter);
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
					agents.get(key).add(new JsonMap().$("name", agentName).$("key", agent.key));
				}

			}
		});
		final JsonList json = new JsonList(callCenters.length);
		for (int i = 0; i < callCenters.length; i++) {
			json.add(new JsonMap().$("name", callCenters[i]).$("agents", agents.get(i)));

		}
		return json;
	}

	private static Integer classify(CallCenter c) {
		return cache.computeIfAbsent(c, callCenter -> {
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

		});
	}

}

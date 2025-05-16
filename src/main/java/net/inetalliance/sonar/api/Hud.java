package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.CallCenter;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.sonar.JsonCronServlet;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.callgrove.obj.Agent.isLocked;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.potion.Locator.forEach;

@WebServlet("/api/hud")
public class Hud
        extends JsonCronServlet {

    private static final Pattern phone = Pattern.compile("7[0-9][0-9][0-9]");

    private static final Map<CallCenter, Integer> cache = new HashMap<>();
    private static final String[] callCenters =
            {"Atlanta", "Phoenix", "Raleigh", "AmeriGlide", "Accounting", "Technology", "Affiliates",
                    "Elevators", "Delstal",
                    "ATC"};

    public Hud() {
        super(1, MINUTES);
    }

    private static Integer classify(CallCenter c) {
        return cache.computeIfAbsent(c, callCenter -> {
            if (callCenter
                    .getName()
                    .startsWith("AZ")) {
                return 1;
            } else if (callCenter
                    .getName()
                    .startsWith("GA")) {
                return 0;
            } else if (callCenter
                    .getName()
                    .startsWith("NC")) {
                return 2;
            }

            return switch (callCenter.id) {
                case 10, 4, 10008, 10012, 10027, 10022 -> 3;
                case 1 -> 5;
                case 6 -> 4;
                case 10025 -> 7;
                case 7 -> 8;
                case 10028, 10024 -> 9;
                default -> null;
            };

        });
    }

    @Override
    protected Json produce() {
        val agents = new HashMap<Integer, JsonList>();
        final Set<String> firstNames = new HashSet<>(8);
        forEach(isLocked.negate(), agent -> {
            try {
                if (phone
                        .matcher(agent.key)
                        .matches()) {
                    val callCenter = agent.getCallCenter();
                    val key = classify(callCenter);
                    if (key != null) {
                        final String agentName;
                        if (key == 6) {
                            agentName = switch (callCenter.id) {
                                case 10 -> format("4Med %s", agent.getFirstName());
                                case 4 -> format("101 %s", agent.getFirstName());
                                case 7 -> format("A1 %s", agent.getFirstName());
                                default -> agent.getFirstName();
                            };
                        } else {
                            if (firstNames.contains(agent.getFirstName())) {
                                agentName = agent.getFirstName() + ' ' + agent
                                        .getLastName()
                                        .charAt(0);
                            } else {
                                agentName = agent.getFirstName();
                            }
                            firstNames.add(agentName);
                        }
                        agents
                                .computeIfAbsent(key, k -> new JsonList())
                                .add(new JsonMap()
                                        .$("name", agentName)
                                        .$("available", !agent.isPaused())
                                        .$("key", agent.key));
                    }

                }
            } catch (Throwable t) {
                log.error(t);
            }
        });
        val json = new JsonList(callCenters.length);
        for (var i = 0; i < callCenters.length; i++) {
            json.add(new JsonMap()
                    .$("name", callCenters[i])
                    .$("agents", agents.computeIfAbsent(i, k -> new JsonList())));
        }
        return json;
    }

    private static final Log log = new Log();

}

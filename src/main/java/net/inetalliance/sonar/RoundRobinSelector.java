package net.inetalliance.sonar;

import com.callgrove.jobs.Hud;
import com.callgrove.obj.Agent;
import com.callgrove.obj.SkillRoute;
import com.callgrove.types.Tier;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;

import java.util.*;

import static java.util.Collections.sort;
import static java.util.Collections.synchronizedMap;

public class RoundRobinSelector {
    private static final Map<Integer,RoundRobinSelector> selectors = synchronizedMap(new HashMap<>());
    private final List<Slot> queue;
    private final SkillRoute route;

    public static RoundRobinSelector $(final SkillRoute route) {
        return selectors.computeIfAbsent(route.id,id-> new RoundRobinSelector(route));
    }
    private RoundRobinSelector(final SkillRoute route) {
        this.route = route;
        this.queue = new ArrayList<>();
        refresh();
    }

    public static void refresh(SkillRoute route) {
        if(selectors.containsKey(route.id)) {
            selectors.get(route.id).refresh();
        }
    }

    private void refresh() {
        queue.clear();
        route.getConfiguredMembers()
                .forEach((key, value) -> {
                    if (key != Tier.NEVER) {
                        value.forEach(a -> queue.add(new Slot(a, key)));
                    }
                });
    }

    private static class Slot implements Comparable<Slot> {
        Slot(Agent agent, Tier tier) {
            this.agent = agent.key;
            this.tier = tier;
        }

        @Override
        public String toString() {
            return String.format("%s: %s [%d]", tier, agent, lastSelection.getMillis());
        }

        String agent;
        Tier tier;
        DateTime lastSelection = DateTime.now().minusYears(10);

        @Override
        public int compareTo(@NotNull Slot slot) {
            if (lastSelection.plusMinutes(5).isAfterNow() || slot.lastSelection.plusMinutes(5).isAfterNow()) {
                return lastSelection.compareTo(slot.lastSelection);
            }
            var c = this.tier.compareTo(slot.tier);
            if (c == 0) {
                c = lastSelection.compareTo(slot.lastSelection);
                if (c == 0) {
                    return agent.compareTo(slot.agent);
                }
                return c;
            }
            return c;
        }
    }

    public String select() {
        return select(0);
    }

    private String select(final int retries) {
        sort(queue);
        var slot = queue.get(0);
        slot.lastSelection = new DateTime();
        if (retries >= queue.size() || Hud.available(slot.agent)) {
            return slot.agent;
        }
        return select(retries + 1);
    }
}

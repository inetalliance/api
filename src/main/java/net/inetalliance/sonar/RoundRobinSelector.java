package net.inetalliance.sonar;

import com.ameriglide.phenix.core.Dates;
import com.callgrove.jobs.Hud;
import com.callgrove.obj.Agent;
import com.callgrove.obj.SkillRoute;
import com.callgrove.types.Tier;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.sort;
import static java.util.Collections.synchronizedMap;

public class RoundRobinSelector {
    private static final Map<Integer, RoundRobinSelector> selectors = synchronizedMap(new HashMap<>());
    private final List<Slot> queue;
    private final SkillRoute route;

    public static RoundRobinSelector $(final SkillRoute route) {
        return selectors.computeIfAbsent(route.id, _ -> new RoundRobinSelector(route));
    }

    private RoundRobinSelector(final SkillRoute route) {
        this.route = route;
        this.queue = new ArrayList<>();
        refresh();
    }

    public static void refresh(SkillRoute route) {
        if (selectors.containsKey(route.id)) {
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
            return String.format("%s: %s [%d]", tier, agent, Dates.toEpochMilli(lastSelection));
        }

        final String agent;
        final Tier tier;
        LocalDateTime lastSelection = LocalDateTime.now().minusYears(10);

        @Override
        public int compareTo(@NotNull Slot slot) {
            var now = LocalDateTime.now();
            if (lastSelection.plusMinutes(5).isAfter(now) || slot.lastSelection.plusMinutes(5).isAfter(now)) {
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
        var slot = queue.getFirst();
        var now = LocalDateTime.now();
        slot.lastSelection = now;
        var hour = now.getHour();
        var afterHours = hour < 7 || hour > 19 ||
                now.getDayOfWeek() == DayOfWeek.SATURDAY ||
                now.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (afterHours || retries > queue.size() || Hud.available(slot.agent)) {
            return slot.agent;
        }
        return select(retries + 1);
    }
}

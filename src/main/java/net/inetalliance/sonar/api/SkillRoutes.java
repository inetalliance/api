package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Site;
import com.callgrove.obj.SkillRoute;
import com.callgrove.obj.SkilledAgent;
import com.callgrove.types.Tier;
import java.util.Comparator;
import java.util.List;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.callgrove.types.Tier.MOBILE;
import static java.util.stream.Collectors.toList;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/skillRoute/*")
public class SkillRoutes
    extends ListableModel.Named<SkillRoute> {

  public SkillRoutes() {
    super(SkillRoute.class);
  }


  private static final Set<String> allowedEditors = Set.of("7000", "7002", "7006", "7007");

  private static final Function<Agent, List<Integer>> visibleRoutes = Funky.memoize(10, agent ->
      agent.getVisibleSites().stream()
          .map(s -> Locator.$$(Site.SiteQueue.withSite(s)))
          .flatMap(Funky::stream)
          .map(sq -> sq.queue.getSkillRoute())
          .distinct()
          .sorted(Comparator.comparing(SkillRoute::getName))
          .map(sq -> sq.id)
          .collect(toList()));

  @Override
  public Query<SkillRoute> all(final Class<SkillRoute> type, final HttpServletRequest request) {
    var loggedIn = Startup.getAgent(request);
    if (loggedIn == null || !allowedEditors.contains(loggedIn.key)) {
      throw new ForbiddenException();
    }
    return Query.in(SkillRoute.class, "id", visibleRoutes.apply(loggedIn)).orderBy("name");
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final Key<SkillRoute> key = getKey(request);
    if (!isEmpty(key.id)) {
      var loggedIn = Startup.getAgent(request);
      if (loggedIn == null || !visibleRoutes.apply(loggedIn).contains(Integer.valueOf(key.id))) {
        if (loggedIn != null) {
          log.warning("%s tried to access the skill route editor for %d", loggedIn.getFullName(),
              key.id);
        }
        throw new ForbiddenException();
      }
      final SkillRoute route = lookup(key, request);
      if (route != null) {

        final JsonMap result = new JsonMap();
        final Map<Tier, Collection<Agent>> members = route.getConfiguredMembers();
        for (final Tier tier : EnumSet.complementOf(EnumSet.of(MOBILE))) {
          result.$(tier.name(),
              members.containsKey(tier) ? members.get(tier).stream()
                  .filter(agent -> agent.isSales() && !agent.isLocked())
                  .map(agent -> new JsonMap()
                      .$("name", agent.getLastNameFirstInitial()).$("key", agent.key))
                  .collect(JsonList.collect) : JsonList.empty);
        }
        respond(response, result);
        return;
      }
    }
    super.get(request, response);
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final Key<SkillRoute> key = getKey(request);
    if (isEmpty(key.id)) {
      throw new BadRequestException("No key");
    }
    final SkillRoute route = lookup(key, request);
    if (route == null) {
      throw new BadRequestException("No route");
    }
    var loggedIn = Startup.getAgent(request);
    if (loggedIn == null || !visibleRoutes.apply(loggedIn).contains(route.id)) {
      if (loggedIn != null) {
        log.warning("%s tried to access the skill route editor for %d",
            loggedIn.getFullName(), key.id);
      }
      throw new ForbiddenException();
    }
    final JsonMap json = JsonMap.parse(request.getInputStream());
    final Tier tier = json.getEnum("tier", Tier.class);
    final String agent = json.get("agent");
    if (tier == null) {
      throw new BadRequestException("No tier");
    }
    if (agent == null) {
      throw new BadRequestException("No agent");
    }

    final SkilledAgent skilledAgent = $1(SkilledAgent.withRoute(route).and(
        SkilledAgent.withAgent(new Agent(agent))));
    if (skilledAgent == null) {
      // No skilled agent record. Create one.
      final SkilledAgent newSkilledAgent = new SkilledAgent();
      newSkilledAgent.skillRoute = route;
      newSkilledAgent.setAgent(new Agent(agent));
      newSkilledAgent.setTier(tier);
      Locator.create(getRemoteUser(request), newSkilledAgent);
    } else {
      Locator.update(skilledAgent, getRemoteUser(request),
          copy -> {
            copy.setTier(tier);
          });
    }
    respond(response, new JsonMap().$("success", true));
  }

  private static final Log log = Log.getInstance(SkillRoutes.class);
}

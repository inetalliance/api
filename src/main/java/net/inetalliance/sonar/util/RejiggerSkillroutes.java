package net.inetalliance.sonar.util;

import com.callgrove.obj.Queue;
import com.callgrove.obj.Site;
import com.callgrove.obj.SkillRoute;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;

import java.util.HashMap;
import java.util.regex.Pattern;

import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.forEach;

public class RejiggerSkillroutes extends DbCli {
    @Override
    protected void exec() {
        var agToUsm = new HashMap<SkillRoute,SkillRoute>();
        forEach(SkillRoute.withNameLike(Pattern.compile("usm.*"), "usm%"), usmRoute -> {
            var agRouteName = usmRoute.getName().substring(4);
            var agRoute = $1(SkillRoute.withName(agRouteName));
            if(agRoute == null) {
                System.out.printf("Could not find matching route for %s%n",usmRoute.getName());
            } else {
                agToUsm.put(agRoute, usmRoute);
            }
        });
        agToUsm.forEach((agRoute,usmRoute) -> {
            forEach(Queue.withSkillRoute(agRoute), queue -> {
                var siteQueue = $1(Site.SiteQueue.withQueue(queue));
                if(siteQueue == null) {
                    System.out.printf("No site for queue %s%n",queue.getName());
                } else {
                    var site = siteQueue.site;
                    if (site.id != 42 && !site.getName().startsWith("AmeriGlide")) {
                        System.out.printf("%s -> %s%n", queue.getName(), usmRoute.getName());
                        Locator.update(queue,"RejiggerSkillroutes", copy -> {
                            copy.setSkillRoute(usmRoute);
                        });
                    }
                }
            });
        });
    }

    public static void main(String[] args) {
        Cli.run(new RejiggerSkillroutes(), args);
    }
}

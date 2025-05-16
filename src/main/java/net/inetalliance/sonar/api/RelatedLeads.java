package net.inetalliance.sonar.api;

import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import static com.callgrove.types.SalesStage.SOLD;
import static java.util.regex.Pattern.compile;
import static net.inetalliance.potion.Locator.forEach;

@WebServlet("/api/relatedLeads/*")
public class RelatedLeads
        extends TypeModel<Opportunity> {

    public RelatedLeads() {
        super(Opportunity.class, compile("/api/relatedLeads/(.*)"));
    }

    @Override
    protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity,
                          final HttpServletRequest request) {
        val list = new JsonList();
        val contact = opportunity.getContact();
        forEach(Contact.withPhoneNumberIn(contact).join(Opportunity.class, "contact"), arg -> {
            if (!arg.id.equals(opportunity.id)) {
                val map = new JsonMap().$("id").$("stage").$("amount").$("created")
                        .$("estimatedClose");
                if (arg.getStage() == SOLD) {
                    map.$("saleDate");
                }
                Info.$(arg).fill(arg, map);
                map.$("site", arg.getSite().getAbbreviation())
                        .$("assignedTo", arg.getAssignedTo().getLastNameFirstInitial())
                        .$("productLine", arg.getProductLine().getName());
                list.add(map);
            }
        });
        return list;
    }
}

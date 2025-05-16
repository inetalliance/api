package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

@WebServlet("/api/contact/*")
public class Contacts
        extends ListableModel<Contact> {

    public Contacts() {
        super(Contact.class);
    }

    private static final Log log = new Log();

    private static JsonMap summary(final Contact contact) {
        return new JsonMap().$("id", contact.id).$("name", contact.getFullName());
    }

    private static JsonMap lead(final Contact contact) {
        var lead = $1(Opportunity
                .withContact(contact)
                .orderBy("created", DESCENDING));
        if (lead == null) {
            log.error("trying to lookup most recent lead for %d, but no leads found", contact.id);
            throw new NotFoundException();
        }
        return new JsonMap().$("id", contact.id).$("lead", lead.id);
    }

    @Override
    public Query<Contact> all(final Class<Contact> type, final HttpServletRequest request) {
        val callId = request.getParameter("call");
        if (isEmpty(callId)) {
            throw new BadRequestException("can't query all contacts at once");
        }
        val call = Locator.$(new Call(callId));
        if (call == null) {
            throw new NotFoundException("Could not find call %s", callId);
        }
        final Query<Contact> q;
        val cid =
                call.getCallerId() == null || isEmpty(call.getCallerId().getNumber()) ? null
                        : call.getCallerId().getNumber();
        if (call.getContact() != null) {
            q = Contact.withId(Contact.class, call.getContact().id);
        } else if (cid != null && cid.length() >= 10) {
            q = Contact.withPhoneNumber(call.getCallerId().getNumber());
        } else {
            q = Query.none(Contact.class);
        }
        return q;
    }

    @Override
    protected Json toJson(Key<Contact> key, Contact contact, HttpServletRequest request) {
        if (request.getParameter("lead") != null) {
            return toJson(request, contact);
        }
        return super.toJson(key, contact, request);
    }

    @Override
    public Json toJson(final HttpServletRequest request, final Contact contact) {
        var summary = request.getParameter("summary") != null;
        var lead = request.getParameter("lead") != null;
        if (summary) {
            return summary(contact);
        } else if (lead) {
            return lead(contact);
        }
        return super.toJson(request, contact);
    }
}

package net.inetalliance.sonar;

import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.funky.functors.types.str.StringFun.empty;

@WebServlet("/api/contact/*")
public class Contacts
		extends ListableModel<Contact> {

	public Contacts() {
		super(Contact.class);
	}

	@Override
	public Query<Contact> all(final Class<Contact> type, final HttpServletRequest request) {
		final String callId = request.getParameter("call");
		if (empty.$(callId)) {
			throw new BadRequestException("can't query all contacts at once");
		}
		final Call call = Locator.$(new Call(callId));
		if (call == null) {
			throw new NotFoundException("Could not find call %s", callId);
		}
		final Query<Contact> q;
		final String cid =
				call.getCallerId() == null || empty.$(call.getCallerId().getNumber())
						? null : call.getCallerId().getNumber();
		if (call.getContact() != null) {
			if (cid != null && cid.length() >= 10) {
				q = Contact.Q.withId(call.getContact().id).and(Contact.Q.withPhoneNumber(call.getCallerId().getNumber()));
			} else {
				q = Contact.Q.withId(call.getContact().id);
			}
		} else if (cid != null && cid.length() >= 10) {
			q = Contact.Q.withPhoneNumber(call.getCallerId().getNumber());
		} else {
			q = Query.none(Contact.class);
		}
		return q;
	}


	@Override
	public F1<Contact, ? extends Json> toJson(final HttpServletRequest request) {
		return request.getParameter("summary") == null ? super.toJson(request) : summary;
	}

	private static final F1<Contact, JsonMap> summary = new F1<Contact, JsonMap>() {
		@Override
		public JsonMap $(final Contact arg) {
			return new JsonMap()
					.$("id", arg.id)
					.$("name", arg.getFullName());
		}
	};
}

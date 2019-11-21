package net.inetalliance.sonar.api;

import static com.callgrove.types.CallDirection.OUTBOUND;
import static com.callgrove.types.CallDirection.QUEUE;
import static com.callgrove.types.Resolution.ANSWERED;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

import com.callgrove.obj.Agent;
import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Queue;
import com.callgrove.obj.Segment;
import com.callgrove.obj.Site;
import com.callgrove.types.CallerId;
import com.callgrove.types.Resolution;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.cli.Cli;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.sonar.api.Leads.Range;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateTime;

@WebServlet("/api/call/*")
public class Calls
    extends ListableModel<Call> {

  private static final Pattern space = compile(" ");
  private static final Random random;
  private static final List<CallerId> callerIds;

  static {
    random = new Random();
    callerIds = new ArrayList<>(200);
    callerIds.add(new CallerId("Benson Dorothy", "9852438557"));
    callerIds.add(new CallerId("Sheffield Kenneth", "8592137216"));
    callerIds.add(new CallerId("Park James", "5053921238"));
    callerIds.add(new CallerId("Flores Grady", "4192146698"));
    callerIds.add(new CallerId("Gates William", "2016290937"));
    callerIds.add(new CallerId("Martin Thomas", "5039196168"));
    callerIds.add(new CallerId("Hess Helen", "5093430048"));
    callerIds.add(new CallerId("Foley Paul", "3176048196"));
    callerIds.add(new CallerId("Lee Sheldon", "5085968844"));
    callerIds.add(new CallerId("Ball Michael", "5016256071"));
    callerIds.add(new CallerId("Ferrer Joseph", "8082627559"));
    callerIds.add(new CallerId("Oney Dorothy", "8473869279"));
    callerIds.add(new CallerId("Mares Michael", "8189061861"));
    callerIds.add(new CallerId("Clark Samantha", "2074719126"));
    callerIds.add(new CallerId("Chou Michael", "5672168653"));
    callerIds.add(new CallerId("Barlow Robert", "8086471452"));
    callerIds.add(new CallerId("Hall Dorothy", "9797942496"));
    callerIds.add(new CallerId("Robinson Loretta", "2074537314"));
    callerIds.add(new CallerId("Ortiz Terrence", "2674401160"));
    callerIds.add(new CallerId("Nguyen Sharon", "8607696170"));
    callerIds.add(new CallerId("Bezanson Brenda", "9375950530"));
    callerIds.add(new CallerId("Snow Jamie", "8314214984"));
    callerIds.add(new CallerId("Klein Jeff", "5027740667"));
    callerIds.add(new CallerId("Carr Lawrence", "2077361797"));
    callerIds.add(new CallerId("Loftin Sarah", "4029252250"));
    callerIds.add(new CallerId("Lewis Bill", "2097085322"));
    callerIds.add(new CallerId("Hopkins Claire", "6025479553"));
    callerIds.add(new CallerId("Kavanagh Ryan", "6078350634"));
    callerIds.add(new CallerId("Johnson Viola", "5204637748"));
    callerIds.add(new CallerId("Ford Toni", "2106461815"));
    callerIds.add(new CallerId("Houghton Gregorio", "2025332285"));
    callerIds.add(new CallerId("Ellsworth Michael", "6194027263"));
    callerIds.add(new CallerId("Towle Mark", "3055623747"));
    callerIds.add(new CallerId("Melvin Maria", "9179894711"));
    callerIds.add(new CallerId("Dorn Charles", "9155994479"));
    callerIds.add(new CallerId("Phillips Sharon", "4849863060"));
    callerIds.add(new CallerId("Shelton Robert", "5622545416"));
    callerIds.add(new CallerId("Morgan Raymond", "8086809228"));
    callerIds.add(new CallerId("Boyle Agatha", "4808212439"));
    callerIds.add(new CallerId("Wright Michael", "3197885413"));
    callerIds.add(new CallerId("Tobey Carolyn", "7184321534"));
    callerIds.add(new CallerId("Witter Carolyn", "7325658777"));
    callerIds.add(new CallerId("Purnell Amanda", "2604093171"));
    callerIds.add(new CallerId("Lawrence Paul", "4342954958"));
    callerIds.add(new CallerId("Hernandez Lara", "6085968676"));
    callerIds.add(new CallerId("Ford Robert", "5618807157"));
    callerIds.add(new CallerId("Haller James", "9376602232"));
    callerIds.add(new CallerId("Gage Kara", "8023516203"));
    callerIds.add(new CallerId("Lee Lawrence", "3366917571"));
    callerIds.add(new CallerId("Lopez Jamie", "6784934743"));
    callerIds.add(new CallerId("Channell David", "2146088656"));
    callerIds.add(new CallerId("Lewis Alfred", "8502285124"));
    callerIds.add(new CallerId("Edwards Sarah", "6152867058"));
    callerIds.add(new CallerId("Stanford Michael", "7122787020"));
    callerIds.add(new CallerId("Ainsworth Peter", "8454788962"));
    callerIds.add(new CallerId("Chang Karen", "2604433304"));
    callerIds.add(new CallerId("Robinson Pierre", "5182728160"));
    callerIds.add(new CallerId("Coker Denver", "8475240258"));
    callerIds.add(new CallerId("Guercio Vernon", "4255902765"));
    callerIds.add(new CallerId("Campbell Raymond", "8647166495"));
    callerIds.add(new CallerId("Bernier Lila", "6615175676"));
    callerIds.add(new CallerId("Wills James", "9706775298"));
    callerIds.add(new CallerId("Morales Robert", "7122268073"));
    callerIds.add(new CallerId("Schantz Mary", "9897919418"));
    callerIds.add(new CallerId("May Linda", "4123597451"));
    callerIds.add(new CallerId("McGraw Charlie", "3375262711"));
    callerIds.add(new CallerId("Moore Sharon", "5708046045"));
    callerIds.add(new CallerId("Williams Joseph", "6027283514"));
    callerIds.add(new CallerId("Thompson Jeff", "4058240299"));
    callerIds.add(new CallerId("Williamson Brad", "4795770685"));
    callerIds.add(new CallerId("Perkins Andrew", "5624136142"));
    callerIds.add(new CallerId("Arrellano Debbie", "6098413567"));
    callerIds.add(new CallerId("Johnson Johanna", "3125703009"));
    callerIds.add(new CallerId("Clark Curtis", "9143764780"));
    callerIds.add(new CallerId("Smyth Jerry", "7028623899"));
    callerIds.add(new CallerId("Damiano Paul", "5702402433"));
    callerIds.add(new CallerId("Carter Elva", "6197410094"));
    callerIds.add(new CallerId("Harvey Elouise", "5707335951"));
    callerIds.add(new CallerId("Cortez Regina", "8574018026"));
    callerIds.add(new CallerId("Tucker Joyce", "8106789907"));
    callerIds.add(new CallerId("Mann Paul", "2139921400"));
    callerIds.add(new CallerId("Smith Robert", "2079675464"));
    callerIds.add(new CallerId("Ramirez Melissa", "3604200924"));
    callerIds.add(new CallerId("Watson Paula", "3022551501"));
    callerIds.add(new CallerId("Rock Regina", "5035980333"));
    callerIds.add(new CallerId("Morgan Henry", "7026802285"));
    callerIds.add(new CallerId("Ridgeway William", "5187821895"));
    callerIds.add(new CallerId("Miller Dale", "9787611357"));
    callerIds.add(new CallerId("Ramirez Sharon", "2178628020"));
    callerIds.add(new CallerId("Fletcher Chad", "7815835506"));
    callerIds.add(new CallerId("Valentine John", "6607851952"));
    callerIds.add(new CallerId("Burton Chris", "7124362462"));
    callerIds.add(new CallerId("Hill Lewis", "5807657884"));
    callerIds.add(new CallerId("Eichhorn Dana", "6464568252"));
    callerIds.add(new CallerId("Hess Debra", "5026899021"));
    callerIds.add(new CallerId("Flower Philip", "3044979250"));
    callerIds.add(new CallerId("Russell Ruby", "2403542499"));
    callerIds.add(new CallerId("Martinez Willis", "6053066192"));
    callerIds.add(new CallerId("Devine Bradley", "2762101735"));
    callerIds.add(new CallerId("Hungate Wiley", "6503496011"));

  }

  public Calls() {
    super(Call.class);
  }

  public static void main(final String[] args) {
    Cli.run(new DbCli() {
      @Override
      protected void exec() {
        final ProgressMeter meter = new ProgressMeter(callerIds.size());
        for (final CallerId callerId : callerIds) {
          meter.increment(callerId.toString());
          final Contact contact = Locator.$1(Contact.withPhoneNumber(callerId.getNumber()));
          if (contact != null) {
            final String[] split = callerId.getName().split("[ ]", 2);
            if (split[0].equals(contact.getLastName()) && split.length == 2 && split[1].equals(
                contact.getFirstName())) {
              forEach(Opportunity.withContact(contact).and(Opportunity.isSold.negate()), opp -> {
                try {
                  Locator.delete("call-sim", opp);
                } catch (Throwable e) {
                  // don't care
                }
              });
              try {
                Locator.delete("call-sim", contact);
              } catch (Throwable t) {
                // no big deal
              }
            }
          }
        }
      }
    }, args);
  }

  @Override
  public Json toJson(final HttpServletRequest request, Call call) {
    final boolean todo = request.getParameter("todo") != null;
    final Agent agent = Startup.getAgent(request);
    if (agent == null) {
      throw new UnauthorizedException();
    }
    final JsonMap map = (JsonMap) super.toJson(request, call);
    final ProductLine productLine =
        call.getQueue() == null ? null : call.getQueue().getProductLine();
    final CallerId remoteCid = call.getRemoteCallerId();
    map.put("remoteCallerId",
        new JsonMap().$("name", remoteCid == null ? null : remoteCid.getName())
            .$("number", remoteCid == null || isEmpty(remoteCid.getNumber())
                ? "Unknown"
                : remoteCid.getNumber()));
    if (!todo && (call.getDirection() == QUEUE || call.getDirection() == OUTBOUND)) {
      final JsonList contacts = new JsonList();
      map.put("contacts", contacts);
      final String number = call.getCallerId().getNumber();
      if (!isEmpty(number) && !"anonymous".equalsIgnoreCase(number) && !"blocked".equalsIgnoreCase(
          number) && number.length() > 4) {
        final Query<Contact> contactQuery = Contact.withPhoneNumber(number);
        forEach(contactQuery.orderBy("id", ASCENDING), contact -> {
          final JsonList leads = new JsonList();
          contacts.add(new JsonMap().$("firstName", contact.getFirstName())
              .$("lastName", contact.getLastName())
              .$("id", contact.id)
              .$("leads", leads));
          final Set<Site> visibleSites = agent.getVisibleSites();
          forEach((visibleSites.isEmpty() ? Query.all(Opportunity.class)
              : Opportunity.withSiteIn(visibleSites)).and(
              Opportunity.withContact(contact)).orderBy("created", ASCENDING), o -> {
            final Agent assignedTo = o.getAssignedTo();
            leads.add(new JsonMap().$("id", o.id)
                .$("stage", o.getStage())
                .$("amount", o.getAmount())
                .$("estimatedClose", o.getEstimatedClose())
                .$("saleDate", o.getSaleDate())
                .$("productLine", o.getProductLineName())
                .$("created", o.getCreated())
                .$("assignedTo",
                    assignedTo == null ? "Nobody" : assignedTo.getLastNameFirstInitial()));

          });
        });
      }
    }
    final Contact contact = call.getContact();
    if (contact != null) {
      map.$("contact", new JsonMap().$("firstName", contact.getFirstName())
          .$("lastName", contact.getLastName()));
    }
    return map
        .$("agent", call.getAgent() == null ? "None" : call.getAgent().getLastNameFirstInitial())
        .$("site", call.getSite() == null ? "None" : call.getSite().getAbbreviation())
        .$("productLine", productLine == null ? "None" : productLine.getAbbreviation());

  }

  @Override
  public Query<Call> all(final Class<Call> type, final HttpServletRequest request) {
    final boolean simulated = request.getParameter("simulated") != null;
    final boolean todo = request.getParameter("todo") != null;
    final Agent agent = Startup.getAgent(request);
    if (agent == null) {
      throw new UnauthorizedException();
    }
    if (simulated && !(agent.isManager() || agent.isTeamLeader())) {
      throw new ForbiddenException("%s tried to access call simulator",
          agent.getLastNameFirstInitial());
    }
    if (simulated) {
      return Call.simulated.and(Call.withAgentIn(agent.getViewableAgents()));
    } else if (todo) {
      return Call.isTodo.and(Call.withAgent(agent)).orderBy("created",DESCENDING).limit(25);
    } else if (isEmpty(request.getParameter("n"))) {
      throw new BadRequestException(
          "Sorry, but you can't ask for all the calls. There's like a bajillion of them.");
    } else {

      final String[] ss = request.getParameterValues("s");
      Query<Call> withSite = ss == null || ss.length == 0
          ? Query.all(Call.class)
          : Call.withSiteIdIn(Arrays.stream(ss).map(Integer::valueOf).collect(toSet()));

      final Range c = getParameter(request, Range.class, "c");
      if (c != null) {
        withSite = withSite.and(Call.inInterval(c.toInterval()));
      }
      return Call.isQueue.and(Call.withSiteIn(agent.getVisibleSites()))
          .and(Startup.callsWithProductLineParameter(request))
          .and(withSite)
          .and(request.getParameter("silent") == null ? Call.isNotSilent : Query.all(Call.class))
          .and(super.all(type, request))
          .orderBy("created", DESCENDING);
    }
  }

  @Override
  protected Json update(final Key<Call> key, final HttpServletRequest request,
      final HttpServletResponse response,
      final Call call, final JsonMap data)
      throws IOException {
    if (call.key.startsWith("sim-") && data.getEnum("resolution", Resolution.class) == ANSWERED) {

      final Segment segment = call.getActiveSegment();
      Locator.update(segment, "call-sim", copy -> {
        copy.setEnded(new DateTime());
      });
      Locator.update(call, "call-sim", arg -> {
        arg.setResolution(ANSWERED);
      });
      return JsonMap.singletonMap("success", true);
    } else if (data.containsKey("todo")) {
      // only allow flipping of t-o-d-o flag.
      return super
          .update(key, request, response, call, new JsonMap().$("todo", data.getBoolean("todo")));
    } else if (data.containsKey("reviewed")) {
      // only allow flipping of reviewed flag.
      return super.update(key, request, response, call,
          new JsonMap().$("reviewed", data.getBoolean("reviewed")));
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public JsonMap create(final Key<Call> key, final HttpServletRequest request,
      final HttpServletResponse response,
      final JsonMap data) {
    if (Funky.isTrue(data.getBoolean("simulated"))) {
      final Agent loggedIn = Startup.getAgent(request);
      if (loggedIn == null) {
        throw new UnauthorizedException();
      }
      if (!loggedIn.isManager() && !loggedIn.isTeamLeader()) {
        throw new ForbiddenException("%s tried to create a simulated call",
            loggedIn.getLastNameFirstInitial());
      }
      final Agent agent = Locator.$(new Agent(data.get("agent")));
      if (loggedIn.isTeamLeader() && !agent.getViewableAgents().contains(agent)) {
        throw new ForbiddenException(
            "%s tried to create a simulated call for a different manager's agent (%s)",
            loggedIn.getLastNameFirstInitial(), agent.key);
      }
      final Site site = Locator.$(new Site(data.getInteger("site")));
      if (site == null) {
        throw new NotFoundException("Could not find site %d", data.getInteger("site"));
      }
      final ProductLine product = Locator.$(new ProductLine(data.getInteger("productLine")));
      if (product == null) {
        throw new NotFoundException("Could not find product line %d",
            data.getInteger("productLine"));
      }
      final Call call = new Call(format("sim-%d", currentTimeMillis()));
      call.setSite(site);
      for (final Queue q : site.getQueues()) {
        if (product.equals(q.getProductLine())) {
          call.setQueue(q);
          break;
        }
      }
      if (call.getQueue() == null) {
        return new JsonMap().$("success", false)
            .$("reason", format("could not find call queue for '%s' on %s", product.getName(),
                site.getAbbreviation()));
      }
      call.setDirection(QUEUE);
      call.setResolution(Resolution.ACTIVE);
      call.setCallerId(callerIds.get(random.nextInt(callerIds.size())));
      call.setCreated(new DateTime());
      call.setAgent(agent);
      Locator.create("call-sim", call);
      final Segment segment = new Segment(call, call.key);
      segment.setAgent(agent);
      segment.setCreated(new DateTime());
      segment.setAnswered(new DateTime());
      Locator.create("call-sim", segment);
      return JsonMap.singletonMap("success", true);
    }
    throw new UnsupportedOperationException();
  }

  @Override
  protected Json toJson(final Key<Call> key, final Call call, final HttpServletRequest request) {
    final JsonMap map = new JsonMap().$("key").$("direction");
    key.info.fill(call, map);
    map.$("todo", call.isTodo());
    switch (call.getDirection()) {
      case OUTBOUND:
      case QUEUE:
        final Site site = call.getSite();
        map.$("site",
            new JsonMap().$("name", site.getName()).$("uri", site.getUri()).$("id", site.id));
        if (call.getQueue() != null) {
          final JsonMap queue = new JsonMap();
          queue.$("name", call.getQueue().getName());
          final ProductLine productLine = call.getQueue().getProductLine();
          if (productLine != null) {
            final String uri = site.getWebpages().get(productLine);
            if (uri != null) {
              queue.$("uri", uri);
            }
          }
          map.$("queue", queue);
        }
        break;
      case INBOUND:
        break;
      case INTERNAL:
        break;
    }
    final CallerId remoteCallerId = call.getRemoteCallerId();
    if (remoteCallerId != null) {
      final String number = remoteCallerId.getNumber();
      final AreaCodeTime time = isEmpty(number) ? null : AreaCodeTime.getAreaCodeTime(number);
      map.$("created", call.getCreated());
      map.$("localTime",
          time == null ? null : time.getDateTimeZone().getOffset(currentTimeMillis()));
      map.$("callerId", new JsonMap().$("name", remoteCallerId.getName()).$("number", number));
      final JsonList contactMatches;
      if (isEmpty(number)) {
        contactMatches = new JsonList();
      } else {
        contactMatches = Locator
            .$$(Contact.withPhoneNumber(number.charAt(0) == '1' ? number.substring(1) : number))
            .stream()
            .map(c -> {
              final JsonMap contactMap = new JsonMap().$("firstName").$("lastName").$("id");
              Info.$(c).fill(c, contactMap);
              return contactMap;
            })
            .collect(Collectors.toCollection(JsonList::new));
      }
      final String[] split =
          isEmpty(remoteCallerId.getName()) ? new String[]{""}
              : space.split(remoteCallerId.getName(), 2);
      contactMatches.add(new JsonMap().$("firstName", split.length == 1 ? null : split[0])
          .$("lastName", split.length == 2 ? split[1] : split[0]));
      map.$("contacts", contactMatches);
    }
    map.$("segments", (JsonList) Locator.$$(Segment.withCall(call).and(Segment.isAnswered)).stream()
        .map(segment -> {
          final JsonMap j = new JsonMap();
          final Agent agent = segment.getAgent();
          if (agent != null) {
            j.$("agent", format("%s %c", agent.getFirstName(), agent.getLastName().charAt(0)));
          }
          if (segment.getTalkTime() != null) {
            j.$("talktime", CallHistory.periodFormatter.print(segment.getTalkTime().toPeriod()));
          }
          return j;
        }).collect(Collectors.toCollection(JsonList::new)));

    return map;
  }

  @Override
  protected Json getAll(final HttpServletRequest request) {
    final JsonMap map = (JsonMap) super.getAll(request);
    final JsonMap filters = Leads.getFilters(request);
    map.$("filters", filters);
    if (request.getParameter("silent") != null) {
      filters.put("silent", new JsonMap().$("silent", "Include Silent Calls"));
    }
    return map;
  }

}

package net.inetalliance.sonar.events;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Opportunity;
import jakarta.websocket.Session;
import lombok.val;
import net.inetalliance.angular.DaemonThreadFactory;
import net.inetalliance.angular.events.Events;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.obj.Opportunity.needsReminding;
import static com.callgrove.obj.Opportunity.withAgentKeyIn;
import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.inetalliance.potion.Locator.forEach;

public class ReminderHandler
        implements MessageHandler, Runnable {

    private static final Log log = new Log();
    public static ReminderHandler $;
    private final Map<String, JsonList> msgs;
    private final Lock lock;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(DaemonThreadFactory.$);

    ReminderHandler() {
        $ = this;
        lock = new ReentrantLock();
        msgs = new LazyMap<>(new HashMap<>(8), s -> new JsonList());
        scheduler.scheduleWithFixedDelay(this, 0, 1, MINUTES);
    }

    private static JsonMap label(final String label, final String phone) {
        return new JsonMap().$("label", label).$("phone", phone);
    }

    @Override
    public JsonMap onMessage(final Session session, final JsonMap msg) {
        val agent = Events.getUser(session).getPhone();
        broadcast(agent);
        return null;
    }

    @Override
    public JsonMap onConnect(final Session session) {
        return onConnect(Events.getUser(session).getPhone());
    }

    public JsonMap onConnect(final String agent) {
        broadcast(agent);
        return null;
    }

    private void broadcast(String agent) {
        lock.lock();
        try {
            msgs.remove(agent);
            forEach(needsReminding(15, MINUTES).and(withAgentKeyIn(singleton(agent))), this::add);
            Events.broadcast("reminder", agent, msgs.get(agent));
        } finally {
            lock.unlock();
        }
    }

    private void add(Opportunity o) {
        val c = o.getContact();
        val dial = new JsonList();
        val shipping = c.getShipping();
        if (shipping != null && isNotEmpty(shipping.getPhone())) {
            dial.add(label("Shipping", shipping.getPhone()));
        }
        val billing = c.getBilling();
        if (billing != null && isNotEmpty(billing.getPhone()) && (shipping == null || !Objects
                .equals(billing.getPhone(),
                        (shipping.getPhone())))) {
            dial.add(label("Billing", billing.getPhone()));
        }
        if (isNotEmpty(c.getMobilePhone()) && (shipping == null || !Objects.equals(c.getMobilePhone(),
                (shipping.getPhone())))) {
            dial.add(label("Mobile", c.getMobilePhone()));
        }
        val contractor = c.getContractor();
        if (contractor != null) {
            if (isNotEmpty(contractor.getOfficePhone())) {
                dial.add(label("Contractor Office", contractor.getOfficePhone()));
            }
            if (isNotEmpty(contractor.getMobilePhone())) {
                dial.add(label("Contractor Mobile", contractor.getMobilePhone()));
            }
        }
        val installer = c.getInstaller();
        if (installer != null) {
            if (isNotEmpty(installer.getOfficePhone())) {
                dial.add(label("Installer Office", installer.getOfficePhone()));
            }
            if (isNotEmpty(installer.getMobilePhone())) {
                dial.add(label("Installer Mobile", installer.getMobilePhone()));
            }
        }

        msgs.get(o.getAssignedTo().key)
                .add(new JsonMap().$("id", o.id)
                        .$("reminder", o.getReminder())
                        .$("stage", o.getStage())
                        .$("dial", dial)
                        .$("contact", o.getContactName())
                        .$("site", o.getSite().getAbbreviation())
                        .$("productLine", o.getProductLine().getAbbreviation().toString())
                        .$("amount", o.getAmount()));
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    @Override
    public void run() {
        val agents = Events.getActiveAgents();
        lock.lock();
        msgs.clear();
        try {
            if (!agents.isEmpty()) {
                forEach(needsReminding(15, MINUTES).and(withAgentKeyIn(agents)), this::add);
                for (var entry : msgs.entrySet()) {
                    val value = entry.getValue();
                    if (!value.isEmpty()) {

                        Events.broadcast("reminder", entry.getKey(), value);
                    }
                }
            }
        } catch (Throwable t) {
            log.error(t);
        } finally {
            lock.unlock();
        }
    }
}

package net.inetalliance.sonar.api;

import com.callgrove.elastix.CallRouter;
import com.callgrove.obj.*;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.obj.AddressPo;
import org.asteriskjava.live.*;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.callgrove.elastix.CallRouter.resolved;
import static com.callgrove.types.CallDirection.OUTBOUND;
import static com.callgrove.types.Resolution.*;
import static com.callgrove.types.SaleSource.PHONE_CALL;
import static java.lang.String.format;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.potion.Locator.create;
import static net.inetalliance.sonar.api.Startup.pbx;
import static org.asteriskjava.live.ChannelState.HUNGUP;

@SuppressWarnings("Duplicates")
@WebServlet("/api/dial")
public class Dial
	extends AngularServlet {

	private static final transient Log log = Log.getInstance(Dial.class);
	private static AtomicInteger id = new AtomicInteger(0);

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final int id = Dial.id.getAndIncrement();

		final Agent loggedIn = Locator.$(new Agent(Auth.getAuthorized(request).getPhone()));
		if (loggedIn == null) {
			throw new ForbiddenException();
		}
		String number = request.getParameter("number");
		if (isEmpty(number)) {
			throw new BadRequestException("Expecting parameter \"number\"");
		}
		// strip () and - from number
		number = number.replaceAll("[-()]", "");
		final String lead = request.getParameter("lead");
		final String loggedInNumber = loggedIn.isForwarded() ? loggedIn.getMobile() : loggedIn.key;
		if (isEmpty(lead)) {
			if (number.length() != 4) {
				throw new BadRequestException("Number should be 4");  // why? -erik (2013-jul-2)
			}
			final String callId = request.getParameter("callId");
			if (isEmpty(callId)) {
				final String dial = format("%s/%s", "SIP", loggedInNumber);
				if (pbx == null) {
					log.info("Not originating call from %s to extension %s because pbx is not configured", dial, number);
				} else {
					pbx.originateToExtensionAsync(dial, "from-internal", number, 1, 30000,
						new CallerId(format("%s %s", loggedIn.getFirstName(),
							loggedIn.getLastName()), loggedInNumber),
						null, null);
				}

			} else {
				final AsteriskChannel channel = pbx.getChannelById(callId);
				if (channel == null) {
					throw new NotFoundException();
				} else {
					final AsteriskChannel linkedChannel = channel.getLinkedChannel();
					if (linkedChannel == null) {
						throw new NotFoundException();
					} else {
						Pattern forAgent = Pattern.compile(String.format("SIP/%s-.*", loggedIn.key));
						if (forAgent.matcher(channel.getName()).matches()) {
							linkedChannel.redirect("from-internal", number, 1);
						} else {
							channel.redirect("from-internal", number, 1);
						}

					}
				}
			}
		} else {
			try {
				final Opportunity opp = Locator.$(new Opportunity(Integer.valueOf(lead)));
				if (opp == null) {
					throw new NotFoundException();
				} else {
					if (opp.getReminder() != null) {
						Locator.update(opp, loggedIn.key, copy -> {
							copy.setReminder(null);
						});
					}
					final Site site = opp.getSite();
					final ProductLine productLine = opp.getProductLine();
					Queue effectiveQueue = null;
					final Set<Queue> queues = site.getQueues();
					if (queues.size() == 1) {
						effectiveQueue = queues.iterator().next();
					} else {
						for (final Queue queue : queues) {
							if (productLine.equals(queue.getProductLine())) {
								effectiveQueue = queue;
								break;
							}
						}
					}
					final String effectivePhone = effectiveQueue != null && isNotEmpty(effectiveQueue.getTollfree())
						? effectiveQueue.getTollfree() : site.getTollfree();
					final CallerId callerId = new CallerId(loggedIn.getLastNameFirstInitial(),
						effectivePhone.charAt(0) == '1' ? effectivePhone.substring(1) :
							effectivePhone
					);
					log.info("Dialing %s for %s on %s/%s [%s]", AddressPo.formatPhoneNumber(number),
						loggedIn.getLastNameFirstInitial(),
						site.getAbbreviation(), callerId.getNumber(),
						effectiveQueue == null ? "main" : effectiveQueue.getAbbreviation());

					final String dial = format("%s/%s", loggedIn.isForwarded() ? "Zap/g0" : "SIP", loggedInNumber);

					final Map<String, String> variables = new HashMap<>(2);
					variables.put("INTRACOMPANYROUTE", "YES");
					variables.put("__CALLGROVE_AGENT", loggedIn.key);
					if (effectiveQueue != null) {
						variables.put("__CALLGROVE_QUEUE", effectiveQueue.key);
					}
					variables.put("__CALLGROVE_SITE", site.id.toString());
					variables.put("__CALLGROVE_OPPORTUNITY", opp.id.toString());
					variables.put("__CALLGROVE_CONTACT", opp.getContact().id.toString());
					if (pbx == null) {
						log.info("Not originating call from %s to %s because pbx is not configured", dial, number);
					} else {
						pbx.originateToExtensionAsync(dial, "from-internal", number, 1, 30000, callerId,
							variables, handle(id, number, callerId, loggedIn, site, opp, effectiveQueue));
					}
				}
			} catch (NumberFormatException nfe) {
				throw new BadRequestException(nfe.getMessage());
			}
		}
	}

	private String printAgent(final Agent agent) {
		return agent == null ? null : agent.getLastNameFirstInitial();
	}

	private OriginateCallback handle(final int id, final String number, final CallerId callerId, final Agent agent, final Site site,
	                                 final Opportunity opp, final Queue effectiveQueue) {
		return new OriginateCallback() {
			@Override
			public void onDialing(AsteriskChannel channel) {

				final Call call = new Call(channel.getId());
				call.setCreated(new DateTime());
				call.setCallerId(new com.callgrove.types.CallerId(callerId));
				call.setAgent(agent);
				log.debug("[%d] agent (param) -> %s", id, printAgent(call.getAgent()));
				if (agent == null) {
					call.setAgent(Locator.$(new Agent(callerId.getNumber())));
					log.debug("[%d] agent (cid) -> %s", id, printAgent(call.getAgent()));
				}
				if (call.getAgent() == null) {
					call.setAgent(Locator.$1(Agent.withLastName(call.getCallerId().getName().split(",")[0])));
					log.debug("[%d] agent (name) -> %s", id, printAgent(call.getAgent()));
				}
				call.setSite(site);
				if (opp != null) {
					call.setOpportunity(opp);
					call.setContact(opp.getContact());
					call.setSource(opp.getSource());
					if (call.getAgent() == null) {
						call.setAgent(opp.getAssignedTo());
						log.debug("[%d] agent (opp) -> %s", id, printAgent(call.getAgent()));
					}
				}
				call.setSource(PHONE_CALL);
				call.setQueue(effectiveQueue);

				call.setResolution(ACTIVE);
				call.setDirection(OUTBOUND);
				create("dial", call);

				final CallRouter.Meta meta = new CallRouter.Meta(call);
				meta.agent = call.getAgent();

				channel.addPropertyChangeListener(new PropertyChangeListener() {
					@Override
					public void propertyChange(final PropertyChangeEvent evt) {
						log.trace("[%s] " + evt.toString(), call.key);
						switch (evt.getPropertyName()) {
							case "dialedChannel": {
								AsteriskChannel dialedChannel = (AsteriskChannel) evt.getNewValue();
								final Segment segment = new Segment(call, dialedChannel.getId());
								segment.setCreated(new DateTime());
								Agent dialedAgent = Locator.$(new Agent(dialedChannel.getCallerId().getNumber()));
								segment.setAgent(dialedAgent == null ? agent : dialedAgent);
								log.debug("[%d:%s] agent (segment) -> %s", id, call.key, printAgent(segment.getAgent()));
								segment.setCallerId(new com.callgrove.types.CallerId("", number));
								create("dial", segment);
								Locator.update(call, "dial", call1 -> {
									final Agent copy = segment.getAgent();
									if (copy != null) {
										call1.setAgent(copy);
										log.debug("[%d:%s] agent (dial) -> %s", id, call1.key, printAgent(call1.getAgent()));
									}
								});
								break;
							}
							case "state": {
								final AsteriskChannel source = (AsteriskChannel) evt.getSource();
								if (HUNGUP.equals(evt.getNewValue()) && !source.getName().endsWith("<ZOMBIE>")) {
									log.debug("[%d:%s] hung up normally", id, call.key);
									Locator.update(call, "dial", copy -> {
										if (!resolved.contains(copy.getResolution())) {
											// look and see if we just came from VoiceMail (history-1 = hangup, history-2 = previous app)
											final List<ExtensionHistoryEntry> history = source.getExtensionHistory();
											if (history.size() > 2 && "macro-vm".equals(
												history.get(history.size() - 2).getExtension().getContext())) {
												copy.setResolution(VOICEMAIL);
											} else {
												copy.setResolution(ANSWERED);
											}
										}
										copy.setDuration(new Duration(copy.getCreated(), new DateTime()));
									});
								}
								break;
							}
							case "name": {
								if (((String) evt.getNewValue()).endsWith("<ZOMBIE>")) {
									log.debug("[%d:%s] transfer, original name was: %s", id, call.key, channel.getName());
									AsteriskChannel masq = pbx.getChannelByName(channel.getName());
									masq.addPropertyChangeListener(this);
									final Segment segment = new Segment(call, masq.getDialedChannel().getId());
									segment.setCreated(new DateTime());
									segment.setAnswered(new DateTime());
									segment.setAgent(Locator.$(new Agent(masq.getDialedChannel().getCallerId().getNumber())));
									log.debug("[%d:%s] agent (xfer segment) -> %s", id, call.key, printAgent(call.getAgent()));
									segment.setCallerId(new com.callgrove.types.CallerId(meta.agent.getLastNameFirstInitial(), meta.agent.key));
									create("dial", segment);
									meta.agent = segment.getAgent();
									Locator.update(call, "dial", copy -> {
										final Agent agent1 = segment.getAgent();
										if (agent1 != null) {
											copy.setAgent(agent1);
											log.debug("[%d:%s] agent (xfer call) -> %s", id, copy.key, printAgent(copy.getAgent()));
										}
										copy.setResolution(ACTIVE);
									});
								}
								break;
							}
							case "linkedChannel": {
								final AsteriskChannel oldValue = (AsteriskChannel) evt.getOldValue();
								final AsteriskChannel newValue = (AsteriskChannel) evt.getNewValue();
								log.trace("[%d:%s] link: %s -> %s", id, call.key, oldValue == null ? null : oldValue.getId(), newValue == null ? null : newValue.getId());
								if (newValue == null) { // unlinking a channel
									assert oldValue != null;
									final Segment segment = Locator.$(new Segment(call, oldValue.getId()));
									if (segment == null) {
										log.error("[%d:%s] could not find segment object %s", id, call.key, oldValue.getId());
									} else {
										Locator.update(segment, "dial", copy -> {
											copy.setEnded(new DateTime());
										});
										Locator.update(call, "dial", copy -> {
											if (meta.agent != null) {
												copy.setAgent(meta.agent);
												log.debug("[%d:%s] agent (link) -> %s", id, copy.key, printAgent(copy.getAgent()));
											}
											copy.setDuration(new Duration(copy.getCreated(), new DateTime()));
										});
									}
								} else { // linking to new channel
									final Segment segment = Locator.$(new Segment(call, newValue.getId()));
									if (segment == null) {
										log.error("[%d:%s] could not find segment object %s", id, call.key, newValue.getId());
									} else {
										Locator.update(segment, "dial", copy -> {
											copy.setAnswered(new DateTime());
										});
									}
								}
							}
						}
					}
				});
			}

			@Override
			public void onSuccess(AsteriskChannel channel) {

			}

			@Override
			public void onNoAnswer(AsteriskChannel channel) {

			}

			@Override
			public void onBusy(AsteriskChannel channel) {

			}

			@Override
			public void onFailure(LiveException cause) {

			}
		};
	}
}

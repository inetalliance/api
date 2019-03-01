package net.inetalliance.sonar;

import com.callgrove.DaemonThreadFactory;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.types.json.Json;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class JsonCronServlet
		extends AngularServlet
		implements Runnable {
	public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, DaemonThreadFactory.$);
	private final int interval;
	private final TimeUnit timeUnit;
	private transient Json content;

	public JsonCronServlet(final int interval, final TimeUnit timeUnit) {
		this.interval = interval;
		this.timeUnit = timeUnit;
	}

	@Override
	public void init(final ServletConfig config)
			throws ServletException {
		super.init(config);
		scheduler.scheduleWithFixedDelay(this, 0, interval, timeUnit);
	}

	@Override
	public void run() {
		content = produce();
	}

	protected abstract Json produce();

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		respond(response, content);
	}

	@Override
	public void destroy() {
		scheduler.shutdown();
	}
}

package net.inetalliance.sonar.events;

import net.inetalliance.angular.DaemonThreadFactory;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.www.ContentType;

import javax.servlet.http.HttpServletResponse;
import javax.websocket.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class ProgressHandler implements MessageHandler {

	public static final OutputStream nullStream = new OutputStream() {
		@Override
		public void write(final int b) {

		}
	};
	private final Executor exec = Executors.newCachedThreadPool(DaemonThreadFactory.$);
	private final AtomicInteger id = new AtomicInteger();

	public static ProgressHandler $;

	public ProgressHandler() {
		super();
		$ = this;
	}

	public void start(final String agent, final HttpServletResponse response, final int max,
	                  final Function<ProgressMeter, Json> proc)
			throws IOException {
		final int jobId = id.getAndIncrement();
		try (Writer writer = response.getWriter()) {
			response.setContentType(ContentType.JSON.toString());
			writer.write(String.format("{\"job\":%d, \"max\":%d}", jobId, max));
			writer.flush();
		}
		exec.execute(new Runnable() {
			@Override
			public void run() {
				final Json result = proc.apply(new ProgressMeter(new PrintStream(nullStream), max) {
					protected void onIncrement(final int delta, final String label) {
						Events.broadcast("progress", agent,
								new JsonMap()
										.$("position", this.position)
										.$("label", label == null ? "" : label)
										.$("job", jobId));
					}

					@Override
					public void increment(final String label, final Object... args) {
						super.increment(label, args);
						setLabel(String.format(label, args));
					}

					@Override
					public void setLabel(final String label) {
						super.setLabel(label);
						Events.broadcast("progress", agent,
								new JsonMap()
										.$("label", label)
										.$("job", jobId));
					}
				});
				Events.broadcast("progress", agent,
						new JsonMap()
								.$("result", result)
								.$("job", jobId));
			}
		});
	}

	@Override
	public JsonMap onMessage(final Session session, final JsonMap msg) {
		return null;
	}

	@Override
	public void destroy() {
		id.set(0);
	}

}

package net.inetalliance.sonar.events;

import com.callgrove.types.*;

class HudStatus {
	public boolean available;
	public CallDirection direction;
	public String callId;

	public void clear() {
		available = false;
		callId = null;
		direction = null;
	}

}

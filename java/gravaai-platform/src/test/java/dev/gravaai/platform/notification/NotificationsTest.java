package dev.gravaai.platform.notification;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.gravaai.platform.dbus.SessionBus;

@Timeout(30)
class NotificationsTest {

	@Test
	@DisplayName("builds the notification client when a server is present")
	void buildsClient() throws Exception {
		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(Notifications.isAvailable(bus), "no notification server on the bus");

			new Notifications(bus);
		}
	}
}

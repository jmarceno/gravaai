package dev.gravaai.platform.dbus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SessionBusTest {

	@Test
	@DisplayName("connects to the session bus with the native unix-socket transport")
	void connectsToSessionBus() throws Exception {
		Optional<SessionBus> maybeBus = SessionBus.tryConnect();
		Assumptions.assumeTrue(maybeBus.isPresent(), "no session bus is available");

		try (SessionBus bus = maybeBus.orElseThrow()) {
			assertNotNull(bus.uniqueName());
			assertTrue(bus.uniqueName().startsWith(":"), bus.uniqueName());
			assertTrue(bus.address().startsWith("unix:"), bus.address());
			assertTrue(bus.isServiceRunning(SessionBus.DBUS_SERVICE));
			assertFalse(bus.isServiceRunning("dev.gravaai.absent.service"));
		}
	}

	@Test
	@DisplayName("resolves the session bus address from the environment")
	void resolvesSessionAddress() {
		String address = SessionBus.sessionAddress();

		assertTrue(address.startsWith("unix:path="), address);
	}
}

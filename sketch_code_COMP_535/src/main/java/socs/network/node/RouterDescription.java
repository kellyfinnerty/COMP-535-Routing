package socs.network.node;

public class RouterDescription {
	// used to socket communication
	String processIPAddress;
	short processPortNumber;
	// used to identify the router in the simulated network space
	String simulatedIPAddress;
	// status of the router
	RouterStatus status;

	public RouterDescription() {

	}

	public RouterDescription(String processIP, short processPort, String simulatedIP, RouterStatus s) {
		processIPAddress = processIP;
		processPortNumber = processPort;
		simulatedIPAddress = simulatedIP;
		status = s;
	}
}

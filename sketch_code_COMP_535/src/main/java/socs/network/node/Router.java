package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;

public class Router {

	volatile protected LinkStateDatabase lsd;

	volatile RouterDescription rd = new RouterDescription();

	// assuming that all routers are with 4 ports
	volatile Link[] ports = new Link[4];
	boolean started = false;
	MultiThreadedServer server;

	public Router(Configuration config) {
		rd.simulatedIPAddress = config.getString("socs.network.router.ip");
		rd.processIPAddress = "127.1.1.0";
		rd.processPortNumber = Short.valueOf(config.getString("socs.network.router.port"));

		lsd = new LinkStateDatabase(rd);

		this.server = new MultiThreadedServer();
		server.start();
	}

	/**
	 * output the shortest path to the given destination ip
	 * <p/>
	 * format: source ip address -> ip address -> ... -> destination ip
	 *
	 * @param destinationIP
	 *            the ip address of the destination simulated router
	 */
	private void processDetect(String destinationIP) {

	}

	/**
	 * disconnect with the router identified by the given destination ip address
	 * Notice: this command should trigger the synchronization of database
	 *
	 * @param portNumber
	 *            the port number which the link attaches at
	 */
	private void processDisconnect(short portNumber) {

	}

	/**
	 * attach the link to the remote router, which is identified by the given
	 * simulated ip; to establish the connection via socket, you need to
	 * identify the process IP and process Port; additionally, weight is the
	 * cost to transmitting data through the link
	 * <p/>
	 * NOTE: this command should not trigger link database synchronization
	 */
	private void processAttach(String processIP, short processPort, String simulatedIP, short weight) {
		
		if(rd.simulatedIPAddress.equals(simulatedIP)){
			System.out.println("Cannot connect Router to itself");
			return; 	//Don't want to attach to itself
		}
		
		
		
		int openPort = -1;	//Check if there's an available neighbor port
		boolean alreadyNeighbor = false;

		// find a non-occupied port and insert
		for (int i = 0; i < 4; i++) {
			if (ports[i] == null) {
				openPort = i;
			}
			else if(ports[i].router2.simulatedIPAddress.equals(simulatedIP)){
				alreadyNeighbor = true;
			}
		}

		// Make sure there is an open neighbor spot and it's not already a neighbor
		if (openPort != -1 && !alreadyNeighbor) {
			RouterDescription rd2 = new RouterDescription(processIP, processPort, simulatedIP);
			ports[openPort] = new Link(rd, rd2);
			
			LSA current = lsd._store.get(rd.simulatedIPAddress);	// update the link weight in LSA
			LinkDescription newLink = new LinkDescription(simulatedIP, processPort, weight);
			current.links.add(newLink);	
		} 
		else if(alreadyNeighbor){
			System.out.println("Unable to attach. Already neighbor.");
		}
		else {
			System.out.println("Unable to attach. All ports are occupied.");
		}

	}

	/**
	 * broadcast Hello to neighbors
	 */
	private void processStart() {
		
		if(started){
			System.out.println("Start can only be run once");
			return;
		}

		LinkedList<HelloSocket> hellos = new LinkedList<HelloSocket>();
		started = true;
		
		for (Link l : ports) {
			// If null or already initialized skip
			if (l != null && l.router2.status != RouterStatus.TWO_WAY) {
				SOSPFPacket helloMsg = new SOSPFPacket((short) 0, rd.simulatedIPAddress, l.router2.simulatedIPAddress,
						rd.simulatedIPAddress, l.router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);

				// start the thread to send HELLO and handle corresponding response
				HelloSocket sendHello = new HelloSocket(l, helloMsg);
				l.router2.status = RouterStatus.INIT;
				sendHello.start();
				hellos.add(sendHello);
			}
		}

		try {
			for (HelloSocket h: hellos){
				h.join();
			}
		}catch(InterruptedException e) {
			System.out.println("Failed to wait for all threads sending HELLO");
	  	}

		

	}

	// a helper function to check whether there is a not occupied port
	public Link notOccupiedPort() {
		for (Link l : ports) {
			if (l == null)
				return l;
		}
		return null;
	}

	/**
	 * attach the link to the remote router, which is identified by the given
	 * simulated ip; to establish the connection via socket, you need to
	 * indentify the process IP and process Port; additionally, weight is the
	 * cost to transmitting data through the link
	 * <p/>
	 * This command does trigger the link database synchronization
	 */
	private void processConnect(String processIP, short processPort, String simulatedIP, short weight) {

	}

	/**
	 * output the neighbors of the routers
	 */
	private void processNeighbors() {
		int i = 1;
		for (Link l : ports) {
			if (l != null && l.router2.status==RouterStatus.TWO_WAY) {
				System.out.println("IP address of neighbor" + i + " " + l.router2.simulatedIPAddress);
				i++;
			}
		}

	}

	/**
	 * disconnect with all neighbors and quit the program
	 */
	private void processQuit() {

	}

	public void terminal() {
		try {
			InputStreamReader isReader = new InputStreamReader(System.in);
			BufferedReader br = new BufferedReader(isReader);
			System.out.print(">> ");
			String command = br.readLine();
			while (true) {
				if (command.startsWith("detect ")) {
					String[] cmdLine = command.split(" ");
					processDetect(cmdLine[1]);
				} else if (command.startsWith("disconnect ")) {
					String[] cmdLine = command.split(" ");
					processDisconnect(Short.parseShort(cmdLine[1]));
				} else if (command.startsWith("quit")) {
					processQuit();
				} else if (command.startsWith("attach ")) {
					String[] cmdLine = command.split(" ");
					processAttach(cmdLine[1], Short.parseShort(cmdLine[2]), cmdLine[3], Short.parseShort(cmdLine[4]));
				} else if (command.equals("start")) {
					processStart();
				} else if (command.equals("connect ")) {
					String[] cmdLine = command.split(" ");
					processConnect(cmdLine[1], Short.parseShort(cmdLine[2]), cmdLine[3], Short.parseShort(cmdLine[4]));
				} else if (command.equals("neighbors")) {
					// output neighbors
					processNeighbors();
				} else {
					// invalid command
					break;
				}
				System.out.print(">> ");
				command = br.readLine();
			}
			isReader.close();
			br.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	class HelloSocket extends Thread {
		// information to be sent
		public String serverID;
		public short portNum;
		public RouterDescription rd2;
		public Link btwlink;
		public SOSPFPacket message;

		public HelloSocket(Link btwlink, SOSPFPacket msg) {
			this.rd2 = btwlink.router2;
			serverID = rd2.simulatedIPAddress;
			portNum = rd2.processPortNumber;
			message = msg;
			this.btwlink = btwlink;
		}

		@Override
		public void run() {
			Socket client = null;
			ObjectInputStream in = null;
			ObjectOutputStream out = null; 
			
			try {				
				client = new Socket(rd2.processIPAddress, portNum);

				out = new ObjectOutputStream(client.getOutputStream());
				out.writeObject(message);

				// get response and check if it is HELLO
				in = new ObjectInputStream(client.getInputStream());
				SOSPFPacket response = (SOSPFPacket) in.readObject();

				if (response.sospfType == 0) {
					System.out.println("received HELLO from " + response.srcIP);

					for (Link l : ports) {
						if (l != null && l.router2.simulatedIPAddress.equals(serverID)) {
							// set router2 status and send HELLO again
							l.router2.status = RouterStatus.TWO_WAY;
							System.out.println("set " + l.router2.simulatedIPAddress + " state to TWO_WAY");
							out.writeObject(message);
						}
					}
				}
			} 
			catch (ClassNotFoundException exp) {
				System.out.println("No valid response message received");
				// remove this neighbor from the list of ports
				for (Link l : ports) {
					if (l.router2.simulatedIPAddress.equals(serverID)) {
						l = null;
						break;
					}
				}
			} 
			catch (Exception e) {
				e.printStackTrace();
				// remove this neighbor from the list of ports
				for (Link l : ports) {
					if (l.router2.simulatedIPAddress.equals(serverID)) {
						l = null;
						break;
					}
				}
			}
			finally {
				try{
					in.close();
					out.close();
					client.close();
				} catch (Exception e){
					System.out.println("Could not close server or I/O stream");
				}
			}

		}
	}

	class ClientMsgHandler extends Thread {
		public Socket server;

		public ClientMsgHandler(Socket serverS) {
			server = serverS;
		}

		@Override
		public void run() {
			ObjectInputStream in = null;
			ObjectOutputStream out = null;
			
			try {
				in = new ObjectInputStream(server.getInputStream());

				// check the received message
				SOSPFPacket receivedMsg = (SOSPFPacket) in.readObject();

				// Hello message
				if (receivedMsg.sospfType == 0) {
					RouterDescription neighbor = null;
					int availableIndex = -1;
					int currIndex = -1;

					// respond message
					SOSPFPacket response = new SOSPFPacket();
					response.sospfType = 0;
					response.routerID = rd.simulatedIPAddress;
					response.srcIP = rd.simulatedIPAddress;
					response.srcProcessIP = rd.processIPAddress;
					response.srcProcessPort = rd.processPortNumber;

					for (int i = 0; i < ports.length; i++) {
						if (ports[i] == null) {
							availableIndex = i;
						}
						else if (ports[i].router2.simulatedIPAddress.equals(receivedMsg.srcIP)) {
							neighbor = ports[i].router2;
							currIndex = i;
						}
					}

					// handle not finding neighbor & not having empty slots
					if (neighbor == null && availableIndex == -1) {
						System.out.println("No available neighbor slots");
						in.close();
						server.close();
						return; // reject & exit
					} 
					else if (neighbor == null && availableIndex != -1) {
						// add neighbor to ports list
						neighbor = new RouterDescription(receivedMsg.srcProcessIP, receivedMsg.srcProcessPort,
								receivedMsg.srcIP);
						ports[availableIndex] = new Link(rd, neighbor);
						currIndex = availableIndex;
					}

					System.out.println("received HELLO from " + ports[currIndex].router2.simulatedIPAddress);
					ports[currIndex].router2.status = RouterStatus.INIT;
					System.out.println("set " + ports[currIndex].router2.simulatedIPAddress + " state to INIT");

					response.dstIP = receivedMsg.srcIP;

					out = new ObjectOutputStream(server.getOutputStream());
					out.writeObject(response);

					SOSPFPacket secReceived = (SOSPFPacket) in.readObject();

					//Check hello message & from the same sourceIP as original
					if (secReceived.sospfType == 0 && secReceived.srcIP.equals(receivedMsg.srcIP)) {
						System.out.println("received HELLO from " + secReceived.srcIP);
						
						ports[currIndex].router2.status = RouterStatus.TWO_WAY;
						System.out.println("set " + ports[currIndex].router2.simulatedIPAddress + " state to TWO_WAY");
					}
					
					
				}
				

				System.out.print(">>");

			} 
			catch (ClassNotFoundException c) {
				System.out.println("Valid response message not received");
			} 
			catch (Exception e) {
				e.printStackTrace();

			}
			finally{
				try{
					in.close();
					server.close();
					out.close();
				} catch(Exception e){
					System.out.println("Could not close server or I/O stream");
				}
			}

		}
	}
	

	class MultiThreadedServer implements Runnable {
		Thread t;
		short port;
		ServerSocket serverSocket;

		MultiThreadedServer() {
			this.port = rd.processPortNumber; // set port as the one from conf file

			try {
				this.serverSocket = new ServerSocket(port);
			} catch (IOException e) {
				System.out.println("Could not listen on port " + this.port);
			} catch (Exception e) {
				System.out.println("Multi-threaded server could not be created");
			}
		}

		public void run() {
			while (true) {
				// New client request --> Create client handler to handle it
				ClientMsgHandler ch;
				try {
					ch = new ClientMsgHandler(serverSocket.accept());
					ch.start();
				} catch (Exception e) {
					System.out.println("Accept and client handler failed: " + port);
				}
			}
			
		}

		public void start() {
			if (t == null) {
				t = new Thread(this);
				t.start();
			}
		}
	}

}

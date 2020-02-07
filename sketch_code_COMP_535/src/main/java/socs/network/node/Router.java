package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedList;

public class Router {

	protected LinkStateDatabase lsd;

	RouterDescription rd = new RouterDescription();

	// assuming that all routers are with 4 ports
	Link[] ports = new Link[4];

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
			System.out.println("Error: Cannot attach Router to itself.");
			return; 	//Don't want to attach to itself
		}
		
		int openPort = -1;	// Check if there's an available neighbor port
		boolean alreadyNeighbor = false;

		// Find a non-occupied port and insert
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

		for (Link l : ports) {
			// If null or already initialized skip
			if (l != null && l.router2.status != RouterStatus.TWO_WAY) {
				SOSPFPacket helloMsg = new SOSPFPacket((short) 0, rd.simulatedIPAddress, l.router2.simulatedIPAddress,
						rd.simulatedIPAddress, l.router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);

				// start the thread to send HELLO and handle corresponding response
				HelloSocket sendHello = new HelloSocket(l, helloMsg);
				sendHello.start();
			}
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
			if (l != null) {
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
			try {
				// try to connect to the server
				System.out.println("Connecting to " + serverID + ", " + portNum);
				
				// Socket client = new Socket(serverID, portNum);
				Socket client = new Socket(rd2.processIPAddress, portNum);

				System.out.println("Connected to " + serverID + ", " + portNum);

				ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
				out.writeObject(message);

				// get response and check if it is HELLO
				ObjectInputStream in = new ObjectInputStream(client.getInputStream());
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
				
				in.close();
				out.close();
				client.close();

			} 
			catch (ClassNotFoundException exp) {
				System.out.println("No valid response message received");
			} 
			catch (Exception e) {
				e.printStackTrace();
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
			try {
				ObjectInputStream in = new ObjectInputStream(server.getInputStream());

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

					ObjectOutputStream out = new ObjectOutputStream(server.getOutputStream());
					out.writeObject(response);

					SOSPFPacket secReceived = (SOSPFPacket) in.readObject();

					//Check hello message
					if (secReceived.sospfType == 0) {
						//Check that received srcIP = neighbor.srcIP?
						System.out.println("received HELLO from " + secReceived.srcIP);
						
						ports[currIndex].router2.status = RouterStatus.TWO_WAY;
						System.out.println("set " + ports[currIndex].router2.simulatedIPAddress + " state to TWO_WAY");
					}
					
					out.close();
				}
				
				in.close();
				server.close();

			} 
			catch (ClassNotFoundException c) {
				System.out.println("Valid response message not received");
			} 
			catch (Exception e) {
				e.printStackTrace();
			}

		}
	}

	class ServerHandler extends Thread {
		@Override
		public void run() {
			// start server socket to listen to others' messages
			try {
				short serverPort = rd.processPortNumber;
				ServerSocket serverS = new ServerSocket(serverPort);

				System.out.println("Server established " + rd.simulatedIPAddress + " port " + serverPort);

				while (true) {
					System.out.println("While loop");
					try {
						// start the thread to handle incoming messages
						ClientMsgHandler msgHandler = new ClientMsgHandler(serverS.accept());
						msgHandler.start();

					} catch (SocketTimeoutException s) {
						System.out.println("Socket timed out!");
						break;
					} catch (Exception e) {
						e.printStackTrace();
						break;
					}
				}
				serverS.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	class MultiThreadedServer implements Runnable {
		Thread t;
		short port;
		ServerSocket serverSocket;

		MultiThreadedServer() {
			System.out.println("Multi-threaded Server created");
			this.port = rd.processPortNumber; // set port as the one from conf file

			try {
				this.serverSocket = new ServerSocket(port);
			} catch (IOException e) {
				System.out.println("Could not listen on port " + this.port);
				// System.exit(-1);
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
					// System.exit(-1);
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

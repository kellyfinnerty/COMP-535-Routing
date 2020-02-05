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

public class Router {

	protected LinkStateDatabase lsd;

	RouterDescription rd = new RouterDescription();

	// assuming that all routers are with 4 ports
	Link[] ports = new Link[4];
	MultiThreadedServer server; // added

	public Router(Configuration config) {
		rd.simulatedIPAddress = config.getString("socs.network.router.ip");
		lsd = new LinkStateDatabase(rd);
		server = new MultiThreadedServer(); // added this
		server.start();
	}

	/**
	 * output the shortest path to the given destination ip
	 * <p/>
	 * format: source ip address -> ip address -> ... -> destination ip
	 *
	 * @param destinationIP
	 *            the ip adderss of the destination simulated router
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
	 * indentify the process IP and process Port; additionally, weight is the
	 * cost to transmitting data through the link
	 * <p/>
	 * NOTE: this command should not trigger link database synchronization
	 */
	private void processAttach(String processIP, short processPort, String simulatedIP, short weight) {
		System.out.println("Attaching");
		// add new "Link" instances to the "port" array
		RouterDescription rd2 = new RouterDescription(processIP, processPort, simulatedIP, null);

		boolean openPort = false;
		for (int i = 0; i < ports.length; i++) {
			if (ports[i] == null) {
				this.ports[i] = new Link(rd, rd2);
				openPort = true;
				break;
			}
		}

		// if all ports are occupied the connection isn't established
		if (openPort) {
			// create link description
			LinkDescription ld = new LinkDescription();
			ld.linkID = simulatedIP;
			ld.portNum = processPort;
			ld.tosMetrics = weight;

			LSA currLsa = lsd._store.get(rd.simulatedIPAddress); // create lsa and add link description
			currLsa.links.add(ld); // add link to currLsa in LinkStateDatabase
		}

	}

	/**
	 * broadcast Hello to neighbors
	 */
	private void processStart() {
		System.out.println("Starting");
		for (int i = 0; i < ports.length; i++) {
			if (ports[i] != null) {
				System.out.println("Sending message to " + ports[i].router2.simulatedIPAddress);
				SOSPFPacket msg = new SOSPFPacket((short)0, rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress, 
						rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress);

				// Need to handle not getting responses, etc.
				try {
					RouterDescription neighbor = ports[i].router2;
					Socket clientSocket = new Socket(neighbor.processIPAddress, neighbor.processPortNumber);

					ObjectInputStream inputStream = new ObjectInputStream(clientSocket.getInputStream());
					ObjectOutputStream outputStream = new ObjectOutputStream(clientSocket.getOutputStream());

					outputStream.writeObject(msg);

					SOSPFPacket response = (SOSPFPacket) inputStream.readObject();
					if (response.sospfType == 1) {
						neighbor.status = RouterStatus.TWO_WAY;
						outputStream.writeObject(msg); // send 2nd hello message
														// (redo?)
					}

					clientSocket.close();
				} catch (Exception e) {
					System.out.println("Exception in start " + e);
					//e.printStackTrace();
				}
			}
		}
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
		for (int i = 0; i < ports.length; i++) {
			if (ports[i] != null) {
				System.out.println("IP Address of the " + ports[i].router2.simulatedIPAddress);
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

	class MultiThreadedServer implements Runnable {
		Thread t;
		short port;
		ServerSocket serverSocket;

		MultiThreadedServer() {
			System.out.println("Multi-threaded Server created");
			this.port = 4444; // figure out random port assignment until one is
								// successful
			try {
				this.serverSocket = new ServerSocket(port);
			} catch (IOException e) {
				System.out.println("Could not listen on port " + port);
				System.exit(-1); // probably should retry another val??
			} catch (Exception e) {
				System.out.println("Multi-threaded server could not be created");
			}
		}

		public void run() {
			while (true) {
				// New client request --> Create client handler to handle it
				ClientHandler ch;
				try {
					ch = new ClientHandler(serverSocket.accept());
					Thread t2 = new Thread(ch);
					t2.start();
				} catch (Exception e) {
					System.out.println("Accept and client handler failed: " + port);
					System.exit(-1);
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

	class ClientHandler implements Runnable {
		Socket clientSocket;
		Thread t;

		ClientHandler(Socket clientSocket) {
			System.out.println("Client Handler created");
			this.clientSocket = clientSocket;
		}

		public void run() {
			ObjectOutputStream objectOutputStream = null;
			ObjectInputStream objectInputStream = null;

			try {
				objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
				objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
			} catch (Exception e) {
				System.out.println("Reading input or getting output failed \n" + e);
				System.exit(-1);
			}

			while (true) {
				try {
					SOSPFPacket serverMsg = (SOSPFPacket)objectInputStream.readObject();

					// if hello message
					if (serverMsg.sospfType == 0) {
						// Check the RouterStatus first??
						handleHelloMessage(serverMsg);
					}

				} catch (Exception e) {
					System.out.println("Could not read client message\n" + e);
				}
			}
		}

		private void handleHelloMessage(SOSPFPacket serverMsg){
			System.out.println("Hello message");
			
			RouterDescription foundNeighbor = null;
			boolean emptyNeighbor = false;
			int availableIndex = -1;
			
			//check if neighbor exists
			for(int i=0; i < ports.length; i++){
				if(ports[i].router2.simulatedIPAddress == serverMsg.srcIP){
					foundNeighbor = ports[i].router2;
					break;
				}
				if(ports[i] == null) {
					emptyNeighbor = true;
					availableIndex = i;
				}
			}
			
			//handle not finding neighbor & not having empty slots
			if(foundNeighbor == null && !emptyNeighbor){
				//reject & exit
				System.out.println("No available neighbor slots");
				return;	
			}
			else if(foundNeighbor == null && emptyNeighbor){
				//add neighbor to ports list
				foundNeighbor = new RouterDescription(serverMsg.srcProcessIP, serverMsg.srcProcessPort, serverMsg.srcIP, null);
				ports[availableIndex] = new Link(rd, foundNeighbor);
			}
			
			//neighbor exists, respond
			System.out.println("received HELLO from " + foundNeighbor.simulatedIPAddress);
			foundNeighbor.status = RouterStatus.INIT;
			System.out.println("set " + foundNeighbor.simulatedIPAddress + "to INIT");
			
			SOSPFPacket msg = new SOSPFPacket((short)0, rd.simulatedIPAddress, foundNeighbor.simulatedIPAddress, 
					rd.simulatedIPAddress, foundNeighbor.simulatedIPAddress);
			
		}

		public void start() {
			if (t == null) {
				Thread t = new Thread(this);
				t.start();
			}
		}
	}

}



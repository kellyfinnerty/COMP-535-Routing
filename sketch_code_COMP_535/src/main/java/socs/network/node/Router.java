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
import java.net.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Vector;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Router {
	
	final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	final Lock readLock = lock.readLock();
	final Lock writeLock = lock.writeLock();

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
		
		// acquire lock
		writeLock.lock();
		
		try{
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
				ports[openPort] = new Link(rd, rd2, weight);
				
	//			LSA current = lsd._store.get(rd.simulatedIPAddress);	// update the link weight in LSA
	//			LinkDescription newLink = new LinkDescription(simulatedIP, processPort, weight);
	//			current.links.add(newLink);	
			} 
			else if(alreadyNeighbor){
				System.out.println("Unable to attach. Already neighbor.");
			}
			else {
				System.out.println("Unable to attach. All ports are occupied.");
			}
		}
		finally{
			// release lock
			writeLock.unlock();
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

		started = true;
		
		// helper method to send out HELLO messages to neighbors
		startHellos();

		// add links with connected neighbor to LSA
		// start LOCK
		writeLock.lock();
		
		try{
			for (Link l:ports) {
				if (l != null && l.router2.status == RouterStatus.TWO_WAY) {
					LinkDescription newLink = new LinkDescription(l.router2.simulatedIPAddress, l.router2.processPortNumber, l.weight);
					lsd._store.get(rd.simulatedIPAddress).links.add(newLink);	
				}
			}
		}
		// end LOCK
		finally{
			writeLock.unlock();
		}
		
		// helper method to send out LSAUPDATE to connected neighbors
		//true because it's the original trigger for the lsaupdates
		startLSAUpdates(true);

	}

	
	
	private void startHellos(){
		LinkedList<HelloSocket> hellos = new LinkedList<HelloSocket>();
		
		// question: start after releasing lock?
		// start LOCK
		writeLock.lock();
		
		try{
			for (int i = 0; i < ports.length; i++) {
				// If null or already initialized skip
				if (ports[i] != null && ports[i].router2.status != RouterStatus.TWO_WAY) {
					 helloMsg = new SOSPFPacket((short) 0, rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress,
							rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);
	
					// start the thread to send HELLO and handle corresponding response
					HelloSocket sendHello = new HelloSocket(ports[i], helloMsg);
					ports[i].router2.status = RouterStatus.INIT;
					sendHello.start();
					hellos.add(sendHello);
				}
			}
		}
		//end LOCK
		finally{
			writeLock.unlock();
		}
		
		
		try {
			for (HelloSocket h: hellos){
				h.join();
			}
		} catch(InterruptedException e) {
			System.out.println("Failed to wait for all threads sending HELLO");
	  	}
	}
	
	//boolean trigger represents if it was the original trigger for LSA update
	private void startLSAUpdates(boolean trigger){
		LinkedList<LSAUpdateSocket> lsaupdates = new LinkedList<LSAUpdateSocket>();
		
		writeLock.lock();
		
		try{
			// then send LSAUpdate
			short lsaupdatemsg = 1;
			String dontForwardTo = rd.simulatedIPAddress;
			
			//create string (routerID) that receivers shouldn't forward to including all 2way neighbors
			for (int i = 0; i < ports.length; i++) {
				if (ports[i] != null && ports[i].router2.status == RouterStatus.TWO_WAY) {
					dontForwardTo += '&' + ports[i].router2.simulatedIPAddress;
				}
			}
			
			lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber++;	//increment curr router's lsa seq number once
	
			//send out message
			for (int i = 0; i < ports.length; i++) {
				// If null or already initialized skip
				if (ports[i] != null && ports[i].router2.status == RouterStatus.TWO_WAY) {
					SOSPFPacket updateMsg = new SOSPFPacket(lsaupdatemsg, dontForwardTo, dontForwardTo,
							rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);
					updateMsg.originalTrigger = trigger;
					updateMsg.lsaArray = new Vector<LSA>();
					updateMsg.lsaArray.add(lsd._store.get(rd.simulatedIPAddress));	//add curr router's lsa
				
					// start the thread to send LSAUPDATE and handle corresponding response
					LSAUpdateSocket sendUpdate = new LSAUpdateSocket(ports[i], updateMsg);
	
					sendUpdate.start();
					lsaupdates.add(sendUpdate);
				}
			}
		}
		finally{
			writeLock.unlock();
		}

		
		try {
			for (LSAUpdateSocket h: lsaupdates){
				h.join();
			}
		} catch(InterruptedException e) {
			System.out.println("Failed to wait for all threads sending LSAUPDATE");
	  	}
	}
	
	
	// a helper function to check whether there is a not occupied port
	private Link notOccupiedPort() {
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
		readLock.lock();
		
		try{
			int i = 1;
			for (Link l : ports) {
				if (l != null && l.router2.status==RouterStatus.TWO_WAY) {
					System.out.println("IP address of neighbor" + i + " " + l.router2.simulatedIPAddress);
					i++;
				}
			}
		} finally {
			readLock.unlock();
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

					for (int i = 0; i < ports.length; i++) {
						if (ports[i] != null && ports[i] != null && ports[i].router2.simulatedIPAddress.equals(serverID)) {
							// set router2 status and send HELLO again
							ports[i].router2.status = RouterStatus.TWO_WAY;
							System.out.println("set " + ports[i].router2.simulatedIPAddress + " state to TWO_WAY");
							out.writeObject(message);
						}
					}
				}
			} 
			catch (ClassNotFoundException exp) {
				System.out.println("No valid response message received");
				// remove this neighbor from the list of ports
				for (int i = 0; i < ports.length; i++) {
					if (ports[i] != null && ports[i].router2.simulatedIPAddress.equals(serverID)) {
						ports[i] = null;
						break;
					}
				}
			} 
			catch (Exception e) {
				//e.printStackTrace();
				System.out.println("Could not connect to " + serverID);
				// remove this neighbor from the list of ports
				for (int i = 0; i < ports.length; i++) {
					if (ports[i] != null && ports[i].router2.simulatedIPAddress.equals(serverID)) {
						ports[i] = null;
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
	
	
	class LSAUpdateSocket extends Thread {
		
		private Link link;
		private SOSPFPacket msg;
		
		public LSAUpdateSocket (Link l, SOSPFPacket msg){
			link = l;
			this.msg = msg;
		}
		
		@Override
		public void run(){
			Socket client = null;
			ObjectOutputStream out = null; 
			
			RouterDescription rd2 = link.router2;
			
			try {				
				client = new Socket(rd2.processIPAddress, rd2.processPortNumber);

				out = new ObjectOutputStream(client.getOutputStream());
				out.writeObject(msg);

			} 
			catch (Exception e) {
				System.out.println("Could not connect to " + rd2.simulatedIPAddress);
				

				// remove from ports
				for (int i = 0; i < ports.length; i++) {
					if (ports[i] != null && ports[i].router2.simulatedIPAddress.equals(rd2.simulatedIPAddress)) {
						ports[i] = null;
					}
				}

				// remove rd2 from LSA of this router 
				int removeIndex = -1;
				for (LinkDescrption d : lsd._store.get(rd.simulatedIPAddress).links) {
					if (d.linkID.equals(rd.simulatedIPAddress)) {
						removeIndex = lsd._store.get(rd.simulatedIPAddress).links.indexOf(d);
					}
				}
				if (removeIndex>-1) lsd._store.get(rd.simulatedIPAddress).links.remove(removeIndex);

				// remove the LSA of rd2 from lsd
				if (lsd._store.gets(rd2)!=null) lsd._store.remove(rd2);


			}
			finally {
				try{
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
		ObjectInputStream in = null;
		ObjectOutputStream out = null;
		
		private int hello = 0;
		private int lsaupdate = 1;

		public ClientMsgHandler(Socket serverS) {
			server = serverS;
		}

		@Override
		public void run() {
			
			try {
				in = new ObjectInputStream(server.getInputStream());
				out = new ObjectOutputStream(server.getOutputStream());

				// check the received message
				SOSPFPacket receivedMsg = (SOSPFPacket) in.readObject();

				
				writeLock.lock();
				
				try{
					// Hello message
					if (receivedMsg.sospfType == hello) {
						helloMessage(receivedMsg);
					}
					else if (receivedMsg.sospfType == lsaupdate){
						// handle lsaupdate
						lsaupdateMessage(receivedMsg);
					}
				} finally {
					writeLock.unlock();
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
				} 
				catch(Exception e){
					System.out.println("Could not close server or I/O stream");
				}
			}

		}
		
		private void helloMessage(SOSPFPacket receivedMsg){
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
				return; // reject & exit, close will happen in finally
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

			SOSPFPacket secReceived;
			
			try {
				out.writeObject(response);
				secReceived = (SOSPFPacket) in.readObject();
			} catch (Exception e){
				System.out.println("Either couldn't send or recieve message");
				return;
			}

			//Check hello message & from the same sourceIP as original
			if (secReceived.sospfType == 0 && secReceived.srcIP.equals(receivedMsg.srcIP)) {
				System.out.println("received HELLO from " + secReceived.srcIP);
				
				ports[currIndex].router2.status = RouterStatus.TWO_WAY;
				System.out.println("set " + ports[currIndex].router2.simulatedIPAddress + " state to TWO_WAY");
			}
		}
	
		

		
		private void lsaupdateMessage(SOSPFPacket msg){
			//lock
			
			boolean forward = false;	//tracks if we should forward the message
			
			//loop through all LSAs received
			for(LSA currMsgLSA : msg.lsaArray){			
				//add LSA to database if not already there or update if newer
				if(!forward){
					forward = addToDatabase(currMsgLSA); //check if it's ever TRUE that we need to forward the message
				}		
				
				//add weight if not already stored in link
				if(isNeighbor(currMsgLSA)){
					updateNeighborWeight(currMsgLSA);
				}	
			}
			
			//if the LSA was new, we need to forward it
			if(forward){
				SOSPFPacket msgToSend = createForwardMsg(msg);
				forwardLSAUpdate(msgToSend, msg.routerID);
				
				//init our own LSA update when receiving the original trigger for lsaupdate
				if(msg.originalTrigger){
					startLSAUpdates(false);	//false bc not original trigger but a response
				}
			}
			
			
		}
		
		
		private void forwardLSAUpdate(SOSPFPacket fwdMsg, String dontForwardTo){
			LinkedList<LSAUpdateSocket> lsaupdates = new LinkedList<LSAUpdateSocket>();
			
			for(Link neighbor : ports){
				if(neighbor == null) { continue; }
				if(dontForwardTo.contains(neighbor.router2.simulatedIPAddress)) { continue; }
				
				fwdMsg.dstIP = neighbor.router2.simulatedIPAddress;
				
				LSAUpdateSocket sendUpdate = new LSAUpdateSocket(neighbor, fwdMsg);
				sendUpdate.start();
				lsaupdates.add(sendUpdate);		
			}
			
			try {
				for (LSAUpdateSocket h: lsaupdates){
					h.join();
				}
			} catch(InterruptedException e) {
				System.out.println("Failed to wait for all threads forwarding LSAUPDATE");
		  	}
		}
		
		
		private SOSPFPacket createForwardMsg(SOSPFPacket msg){
			
			SOSPFPacket newMsg = new SOSPFPacket();
			newMsg.srcProcessIP = rd.processIPAddress;
			newMsg.srcProcessPort = rd.processPortNumber;
			newMsg.srcIP = rd.simulatedIPAddress;	//should this be changed??
			newMsg.sospfType = 1;
			newMsg.lsaArray = msg.lsaArray;
			
			String dontForwardTo = msg.routerID;
			String newDontForwardTo = dontForwardTo;
			
			for(Link neighbor : ports){
				if(neighbor == null) { continue; }
				
				String neighborSimIP = neighbor.router2.simulatedIPAddress;
				
				//don't send to original sender and anyone in string
				if(!dontForwardTo.contains(neighborSimIP)){
					newDontForwardTo += "&" + neighborSimIP;
				}
								
			}
			
			newMsg.neighborID = newDontForwardTo;
			return newMsg;
		}
		
		
		private boolean isNeighbor(LSA lsa){

			for(Link neighbor : ports){
				if(neighbor != null && neighbor.router2.simulatedIPAddress.equals(lsa.linkStateID)){
					return true;
				}
			}
			
			return false;
		}
		
		private void updateNeighborWeight(LSA currMsgLSA){
			boolean alreadyAdded = false;
			
			for(LinkDescription myNeighbor : lsd._store.get(rd.simulatedIPAddress).links){
				// if LinkDescription already in LSA links
				if(myNeighbor.linkID.equals(currMsgLSA.linkStateID)){
					alreadyAdded = true;
					
					//Loop through to find weight in the neighbor's links
					for(LinkDescription currMsgLSALink : currMsgLSA.links){
						if(currMsgLSALink.linkID.equals(rd.simulatedIPAddress)){
							myNeighbor.tosMetrics = (int)currMsgLSALink.tosMetrics;
							break;
						}
					}
								
				}
			}
			
			//(Possibly) finally getting the weight after receiving as a server
			//Now we will add them as a neighbor in our LSA
			if(!alreadyAdded){
				int portNum = 0;
				int currMsgLSAWeight = 0;
				
				//find the received lsa's stored link to us and get their values
				for(LinkDescription l : currMsgLSA.links){
					if(l.linkID.equals(currMsgLSA.linkStateID)){
						portNum = l.portNum;
						currMsgLSAWeight = l.tosMetrics;
					}
				}
				
				LinkDescription newLink = new LinkDescription(currMsgLSA.linkStateID, portNum, currMsgLSAWeight);
				lsd._store.get(rd.simulatedIPAddress).links.add(newLink);
			}
		}
		
		
		private boolean addToDatabase(LSA currMsgLSA){
			
			// if LSA is NOT in database, add it
			if(lsd._store.get(currMsgLSA.linkStateID) == null){
				lsd._store.put(currMsgLSA.linkStateID, currMsgLSA);	
				return true;	//should forward bc LSA not in database
			}
			else{
				// if currMsgLSA's sequence number > the currMsgLSAently stored one, update the lsa
				if(lsd._store.get(currMsgLSA.linkStateID).lsaSeqNumber < currMsgLSA.lsaSeqNumber){
					lsd._store.replace(currMsgLSA.linkStateID, lsd._store.get(currMsgLSA.linkStateID), currMsgLSA);	
					return true;	// should forward bc LSA is newer
				}
			}
			
			return false;	//shouldn't forward
			
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

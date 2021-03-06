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
	
	// use read write lock to ensure there are no more than one thread editing the public fields
	// and there are no thread editing these fields when other threads are reading them
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
		String result = lsd.getShortestPath(destinationIP);
		System.out.println(result);
		System.out.print(">>");
	}

	/**
	 * disconnect with the router identified by the given destination ip address
	 * Notice: this command should trigger the synchronization of database
	 *
	 * @param portNumber
	 *            the port number which the link attaches at
	 */
	private void processDisconnect(short portNumber) {
		// return if there is either no router connected with this port or the status is not TWO_WAY
		if (ports[portNumber]==null || ports[portNumber].router2.status != RouterStatus.TWO_WAY) {
			System.out.println("Port "+portNumber+" has no neighbor connected yet");
			return;
		}
		
		LSA thisRd = lsd._store.get(rd.simulatedIPAddress);
		LSA remoteRd = lsd._store.get(ports[portNumber].router2.simulatedIPAddress);
		
		// remove the link to remote router from current router's LSA
		LinkDescription remLd = null;
		for(LinkDescription ld : thisRd.links) {
			 if(ld.linkID.equals(ports[portNumber].router2.simulatedIPAddress)) remLd = ld;
		}
		thisRd.links.remove(remLd);
		thisRd.lsaSeqNumber ++;
		
		// remove the link to current router from remote router's LSA
		remLd = null;
		for(LinkDescription ld : remoteRd.links) {
			 if(ld.linkID.equals(rd.simulatedIPAddress)) remLd = ld;
		}
		remoteRd.links.remove(remLd);
		remoteRd.lsaSeqNumber ++;
		
		// send LSAUpdate message of current and remote routers
		sendRemLSAUpdate(ports[portNumber].router2.simulatedIPAddress);
		// empty this port
		ports[portNumber] = null;
	}
	
	// a helper method to send LSAUpdates to all other neighbors
	public void sendRemLSAUpdate(String remoteRouter) {
		LinkedList<LSAUpdateSocket> lsaupdates = new LinkedList<LSAUpdateSocket>();

		writeLock.lock();
		
		try{
			short lsaupdatemsg = 1;
			String dontForwardTo = rd.simulatedIPAddress;
			
			//send out message
			for (int i = 0; i < ports.length; i++) {
				// If null or already initialized skip
				if (ports[i] != null && ports[i].router2.status == RouterStatus.TWO_WAY) {
					SOSPFPacket updateMsg = new SOSPFPacket(lsaupdatemsg, dontForwardTo, dontForwardTo,
							rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);
					updateMsg.originalTrigger = true;
					updateMsg.lsaArray = new Vector<LSA>();
					updateMsg.lsaArray.add(lsd._store.get(rd.simulatedIPAddress));	//add curr router's lsa
					if (remoteRouter != null) updateMsg.lsaArray.add(lsd._store.get(remoteRouter));	//add remote router's lsa
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


	/**
	 * attach the link to the remote router, which is identified by the given
	 * simulated ip; to establish the connection via socket, you need to
	 * identify the process IP and process Port; additionally, weight is the
	 * cost to transmitting data through the link
	 * <p/>
	 * NOTE: this command should not trigger link database synchronization
	 * 
	 * returns int indicating neighbor index (-1 if not added)
	 */
	private int processAttach(String processIP, short processPort, String simulatedIP, short weight) {
		int finalindex = -1;
		
		// acquire lock
		writeLock.lock();
		
		try{
			if(rd.simulatedIPAddress.equals(simulatedIP)){
				System.out.println("Cannot connect Router to itself");
				return finalindex; 	//Don't want to attach to itself
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
					finalindex = i;
				}
			}
	
			// Make sure there is an open neighbor spot and it's not already a neighbor
			if (openPort != -1 && !alreadyNeighbor) {
				RouterDescription rd2 = new RouterDescription(processIP, processPort, simulatedIP);
				ports[openPort] = new Link(rd, rd2, weight);
				finalindex = openPort;
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
		
		return finalindex;
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
		
		boolean changeofstate = false;	//check if a neighbor was actually added
		
		try{
			//add if not already a link
			for (Link l:ports) {
				if (l != null && l.router2.status == RouterStatus.TWO_WAY) {
					boolean newlink = true;
					
					//check if we've previously added it to links, if so, skip
					for(LinkDescription ld : lsd._store.get(rd.simulatedIPAddress).links) {
						 if(ld.linkID.equals(l.router2.simulatedIPAddress)) newlink = false;
					}
					
					if(newlink){
						LinkDescription newLink = new LinkDescription(l.router2.simulatedIPAddress, l.router2.processPortNumber, l.weight);
						lsd._store.get(rd.simulatedIPAddress).links.add(newLink);	
						
						changeofstate = true;
					}
				}
			}
		}
		// end LOCK
		finally{
			writeLock.unlock();
		}
		
		// helper method to send out LSAUPDATE to connected neighbors
		//true because it's the original trigger for the lsaupdates
//		startLSAUpdates(true);
		if(changeofstate){
			boolean originaltrigger = true;
			startLSAUpdates(originaltrigger);
		}


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
					 SOSPFPacket helloMsg = new SOSPFPacket((short) 0, rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress,
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
			
			// in case this LSAUpdate message is for quit()
			if (!lsd._store.containsKey(rd.simulatedIPAddress)) return;
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
	

	/**
	 * attach the link to the remote router, which is identified by the given
	 * simulated ip; to establish the connection via socket, you need to
	 * indentify the process IP and process Port; additionally, weight is the
	 * cost to transmitting data through the link
	 * <p/>
	 * This command does trigger the link database synchronization
	 */
	private void processConnect(String processIP, short processPort, String simulatedIP, short weight) {
		System.out.println("Started " + started);
		
		//check if start's been run yet
		if(!started) {
			System.out.println("Not started yet. Can't connect.");
			return;
		}
		
		// check for empty neighbor slot
		int index = processAttach(processIP, processPort, simulatedIP, weight);
		
		// wasn't already a neighbor or added quit, or connection not started
		if(index == -1 || ports[index].router2.status == RouterStatus.TWO_WAY) return;
		
			
		writeLock.lock();
		
		try{
			SOSPFPacket helloMsg = new SOSPFPacket((short) 0, rd.simulatedIPAddress, ports[index].router2.simulatedIPAddress,
			rd.simulatedIPAddress, ports[index].router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);
	
			// start the thread to send HELLO and handle corresponding response
			HelloSocket sendHello = new HelloSocket(ports[index], helloMsg);
			ports[index].router2.status = RouterStatus.INIT;
			sendHello.start();
			
			try{ sendHello.join();}
			catch(Exception e){	System.out.println("Catch thread didn't finish"); }
		}
		finally{
			writeLock.unlock();
		}
			
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
		LSA thisRd = lsd._store.get(rd.simulatedIPAddress);
		
		// remove all other links from current router's LSA
		// only keep itself in LSA
		while (thisRd.links.size()>1) thisRd.links.removeLast();
		thisRd.lsaSeqNumber ++;
		
		// remove current router's LinkDescription from all other router's LSA
		for (LSA rlsa: lsd._store.values()) {
			LinkDescription rmvLink = null;
			for (LinkDescription l: rlsa.links) {
				if (l.linkID.equals(rd.simulatedIPAddress)) rmvLink = l;
			}
			if (rmvLink!=null) {
				rlsa.links.remove(rmvLink);
				rlsa.lsaSeqNumber++;
			}
		}
		
		// send LSAupdate so that all other can delete current router from all LSA
		LinkedList<LSAUpdateSocket> lsaupdates = new LinkedList<LSAUpdateSocket>();
		writeLock.lock();
		
		try{
			short lsaupdatemsg = 1;
			String dontForwardTo = rd.simulatedIPAddress;
			
			//send out message
			for (int i = 0; i < ports.length; i++) {
				// If null or already initialized skip
				if (ports[i] != null && ports[i].router2.status == RouterStatus.TWO_WAY) {
					SOSPFPacket updateMsg = new SOSPFPacket(lsaupdatemsg, dontForwardTo, dontForwardTo,
							rd.simulatedIPAddress, ports[i].router2.simulatedIPAddress, rd.processIPAddress, rd.processPortNumber);
					updateMsg.originalTrigger = true;
					updateMsg.lsaArray = new Vector<LSA>();
					// add all LSA of current router
					for (LSA sendlsa: lsd._store.values()) updateMsg.lsaArray.add(sendlsa);
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
			for (LSAUpdateSocket h: lsaupdates) h.join();
		} catch(InterruptedException e) {
			System.out.println("Failed to wait for all threads sending LSAUPDATE");
	  	} finally {
	  		System.exit(0);
	  	}
		
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
				} else if (command.startsWith("lsd ")) {
					System.out.println(lsd.toString());;
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
				} else if (command.startsWith("connect ")) {
					String[] cmdLine = command.split(" ");
					processConnect(cmdLine[1], Short.parseShort(cmdLine[2]), cmdLine[3], Short.parseShort(cmdLine[4]));
				} else if (command.equals("neighbors")) {
					// output neighbors
					processNeighbors();
				} else if (command.equals("lsd")){
					System.out.println(lsd.toString());
					
				} else if (command.equals("ports")){
					for(Link l : ports){
						if(l!= null) System.out.println(l.router2.simulatedIPAddress);
					}
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
			}// end of try block

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
				for (LinkDescription d : lsd._store.get(rd.simulatedIPAddress).links) {
					if (d.linkID.equals(rd.simulatedIPAddress)) {
						removeIndex = lsd._store.get(rd.simulatedIPAddress).links.indexOf(d);
					}
				}
				if (removeIndex>-1) lsd._store.get(rd.simulatedIPAddress).links.remove(removeIndex);

				// remove the LSA of rd2 from lsd
				if (lsd._store.get(rd2.simulatedIPAddress)!=null) 
					lsd._store.replace(rd2.simulatedIPAddress, null);

			}//end of try block
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
					out.close();
					server.close();
					
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
				
				addLink(receivedMsg.srcIP, receivedMsg.srcProcessPort, ports[currIndex].weight);
			}
		}
	
		private void addLink(String srcIP, short procPort, short weight){
			LinkDescription newld = new LinkDescription();
			newld.linkID = srcIP;
			newld.portNum = procPort;
			newld.tosMetrics = weight;
			
			lsd._store.get(rd.simulatedIPAddress).links.add(newld);
		}

		
		private void lsaupdateMessage(SOSPFPacket msg){
			//tracks if we should forward the message
			boolean forward = false;	
			//tracks if current router need to send an updated LSA of itself
			boolean includeItself = false;	
			// tracks if current msg is for removing a neighbor of current router
			boolean toRemove = false;
			// tracks if current msg is for another router to quit
			boolean toQuit = false;
			
			// if lsaArray contains two LSA and both has smaller size than those in current lsd
			// and one of the two LSA belons to the current router
			if (msg.lsaArray.size()==2 && isRemLSA(msg.lsaArray.get(0), msg) 
					&& isRemLSA(msg.lsaArray.get(1), msg)
					&& (msg.lsaArray.get(0).linkStateID.equals(rd.simulatedIPAddress) 
					|| msg.lsaArray.get(1).linkStateID.equals(rd.simulatedIPAddress)))
				toRemove = true;
			
			// if there is a LSA in LSAUpdate message that has size 0, 
			// then this is a message generated because a router quits
			String rmvIP = "";
			for (LSA cLsa: msg.lsaArray) {
				if (cLsa.links.size()==0) {
					toQuit = true;
					rmvIP = new String(cLsa.linkStateID);
				}
			}
			
			//loop through all LSAs received
			for(LSA currMsgLSA : msg.lsaArray){			
				//add LSA to database if not already there or update if newer
				//check if it's ever TRUE that we need to forward the message
				if(!forward) forward = addToDatabase(currMsgLSA); 
				else if(lsd._store.get(currMsgLSA.linkStateID).lsaSeqNumber < currMsgLSA.lsaSeqNumber) 
					lsd._store.replace(currMsgLSA.linkStateID, currMsgLSA);
				
				//check if it's ever TRUE that current router need to forward itself
				if(!includeItself) includeItself = forwardItself(currMsgLSA); 
				
				//add weight if not already stored in link
				if(isNeighbor(currMsgLSA)) updateNeighborWeight(currMsgLSA);
			}
			

			//if the LSA was new, we need to forward it
			if(forward){
				SOSPFPacket msgToSend = createForwardMsg(msg);
				forwardLSAUpdate(msgToSend, msg.routerID);

				//init our own LSA update when receiving the original trigger for lsaupdate
				if(msg.originalTrigger) startLSAUpdates(false);	//false bc not original trigger but a response
					
				
			}
			
			// create a new round of LSA update including only the latest version of itself
			if(includeItself) startLSAUpdates(true);
			
			// remove the LinkDescription from current router's ports
			if (toRemove) rmvFromPort(msg.lsaArray);
			
			// for quit()
			if (toQuit) {
				// remove that router from ports array
				short tormv = -1;
				for(short i=0; i<4; i++){
					if(ports[i] != null && ports[i].router2.simulatedIPAddress.equals(rmvIP)) 
						tormv = i;
				}
				if (tormv>-1) ports[tormv] = null;
				
			}
		}
		
		// to decide if this LSA of LSAUpdate message is to remove a LinkDescription
		private boolean isRemLSA(LSA curLSA, SOSPFPacket message) {
			return ( (lsd._store.get(message.lsaArray.get(0).linkStateID).lsaSeqNumber 
					< message.lsaArray.get(0).lsaSeqNumber ) && 
					(lsd._store.get(message.lsaArray.get(0).linkStateID).links.size() 
							> message.lsaArray.get(0).links.size() ) );
		}
		
		private void forwardLSAUpdate(SOSPFPacket fwdMsg, String dontForwardTo){
			LinkedList<LSAUpdateSocket> lsaupdates = new LinkedList<LSAUpdateSocket>();

			for(Link neighbor : ports){
				// don't forward to non-neighbor ports
				if(neighbor == null || neighbor.router2.status != RouterStatus.TWO_WAY) continue; 
				// don't forward if this neighbor is contained in string dontForwardTo
				if(checkIfDontforward(dontForwardTo,neighbor.router2.simulatedIPAddress)) continue; 

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
		
		
		private boolean checkIfDontforward(String dontfwd, String rtIP) {
			String[] dontfwdRouters = dontfwd.split("&");
			for (String s: dontfwdRouters) {
				if (s.equals(rtIP)) return true;
			}
			return false;
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
			newMsg.routerID = newDontForwardTo;
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
		
		// Given an array of LSA, check if that contains a previous neighbor of current router
		// if so, remove that previous neighbor from ports
		private void rmvFromPort(Vector<LSA> lsaArray) {
			short tormv = -1;
			for(short i=0; i<4; i++){
				if(ports[i] != null && 
						(ports[i].router2.simulatedIPAddress.equals(lsaArray.get(0).linkStateID) 
						|| ports[i].router2.simulatedIPAddress.equals(lsaArray.get(1).linkStateID))){
					tormv = i;
				}
			}
			if (tormv>-1) ports[tormv] = null;
		}
		
		
		private void updateNeighborWeight(LSA currMsgLSA){
			// in case of currMsgLSA is for quit()
			for(LinkDescription myNeighbor : lsd._store.get(rd.simulatedIPAddress).links){
				// if LinkDescription already in LSA links
				if(myNeighbor.linkID.equals(currMsgLSA.linkStateID)){
					//Loop through to find weight in the neighbor's links
					for(LinkDescription currMsgLSALink : currMsgLSA.links){
						if(currMsgLSALink.linkID.equals(rd.simulatedIPAddress)){
							myNeighbor.tosMetrics = (int)currMsgLSALink.tosMetrics;
							break;
						}
					}
								
				}
			}
		}
		
		
		private boolean addToDatabase(LSA currMsgLSA){
			// if LSA is NOT in database, add it
			if(lsd._store.get(currMsgLSA.linkStateID) == null){
				return true;	//should forward bc LSA not in database
			}
			// if currMsgLSA's sequence number > the currMsgLSAently stored one, update the LSA
			else if(lsd._store.get(currMsgLSA.linkStateID).lsaSeqNumber < currMsgLSA.lsaSeqNumber) {
				lsd._store.replace(currMsgLSA.linkStateID, currMsgLSA);
				return true;	// should forward bc LSA is newer
			}
			
			return false;	//shouldn't forward
		}

			
		private boolean forwardItself(LSA currMsgLSA){
			if(lsd._store.get(currMsgLSA.linkStateID) == null){
				// add this LSA into lsd of current router and prepare for forwarding LSA of current router
				lsd._store.put(currMsgLSA.linkStateID, currMsgLSA);	
				return true;	
			}
			return false;
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
		
		public void close(){
			try{
				serverSocket.close();
			}
			catch(Exception e){
				System.out.println("couldn't close server socket");
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

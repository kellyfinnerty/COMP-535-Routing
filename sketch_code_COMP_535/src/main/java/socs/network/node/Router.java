package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);
  }

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {

  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {

  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {

	  
	  // create new Link object in ports
	  RouterDescription rd1 = new RouterDescription(processIP, processPort, simulatedIP,RouterStatus.INIT);
	  Link newPort = new Link(rd, rd1); // needs second RouterDescription
	  
	  for (int i=0; i<4;i++) {
		  if (ports[i]==null) {
			  ports[i] = newPort;
			  break;
		  }
		  if (i==3) System.out.println("Unable to attach. All ports are occupied.");
	  }
	  
	  LSA current = lsd._store.get(rd.simulatedIPAddress);
	  LinkDescription newLink = new LinkDescription();
	  newLink.portNum = processPort;
	  newLink.tosMetrics = weight;
	  newLink.linkID = processIP;
	  // how to name the linkID??? -- the IP of the destination of link
	  current.links.add(newLink);

  }

  /**
   * broadcast Hello to neighbors
   */
  private void processStart() {
	  for (Link l: ports) {
		// generate a HELLO message
		  SOSPFPacket helloMsg = new SOSPFPacket();
		  helloMsg.sospfType = 0;
		  helloMsg.routerID = rd.simulatedIPAddress;
		  helloMsg.srcIP = rd.simulatedIPAddress;
		  helloMsg.srcProcessIP = rd.processIPAddress;
		  helloMsg.srcProcessPort = rd.processPortNumber;
		  helloMsg.dstIP = l.router2.processIPAddress;
		  // what is neighborID??? -- a long string of all neighbors, or a list
		  HelloSocket sendHello = new HelloSocket(l, helloMsg);
		  sendHello.start();
	  }
	  
	  
	  // start server socket
	  try {
		  ServerSocket serverS = new ServerSocket(1111);
		  while (true) {
			  try
			  {
//				  System.out.println("Waiting for router "+simulatedIP);
				  Socket serverSide = serverS.accept();
				  System.out.println("Accepted");
				  ObjectInputStream in = new ObjectInputStream(serverSide.getInputStream());
				  
				  SOSPFPacket receivedMsg = (SOSPFPacket)in.readObject();
				  if (receivedMsg.sospfType==0) {
					  boolean alreadyNeighbor = false;
					  SOSPFPacket response = new SOSPFPacket();
					  response.sospfType = 0;
					  response.routerID = rd.simulatedIPAddress;
					  response.srcIP = rd.simulatedIPAddress;
					  response.srcProcessIP = rd.processIPAddress;
					  response.srcProcessPort = rd.processPortNumber;
					  
					  for (Link l:ports) {
						  if (l.router2.processIPAddress.equals(receivedMsg.srcProcessIP)) {
							  alreadyNeighbor = true;
							  l.router2.status = RouterStatus.INIT;
							  
							  response.dstIP = l.router2.simulatedIPAddress;
						  }
					  }
					  if (!alreadyNeighbor) {
						  Link notOccupied = notOccupiedPort();
						  if (notOccupied==null) continue;
						  notOccupied.router1 = rd;
						  notOccupied.router2 = new RouterDescription(receivedMsg.srcProcessIP,receivedMsg.srcProcessPort,receivedMsg.srcIP,RouterStatus.INIT);
						  response.dstIP = receivedMsg.srcIP;
						  
					  }
					  OutputStream respToServer = serverSide.getOutputStream();
					  ObjectOutputStream out = new ObjectOutputStream(respToServer);
					  out.writeObject(response);
				  }
			  }catch(ClassNotFoundException c)
			  {
				  System.out.println("Valid response message not received");
				  break;
			  }catch(SocketTimeoutException s)
			  {
				  System.out.println("Socket timed out!");
				  break;
			  }catch(IOException e)
			  {
				  e.printStackTrace();
				  break;
			  }
			  
		  }
	  }catch(IOException e)
	  {
		  e.printStackTrace();
	  }
  

  }
  
  
  public Link notOccupiedPort() {
	  for (Link l : ports) {
		  if (l==null) return l;
	  }
	  return null;
  }
  

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP, short weight) {

  }

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
	  int i=1;
	  for (Link l: ports) {
		  if (l!=null) {
			  System.out.println("IP address of neighbor"+i+" "+l.router2.simulatedIPAddress);
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
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("start")) {
          processStart();
        } else if (command.equals("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("neighbors")) {
          //output neighbors
          processNeighbors();
        } else {
          //invalid command
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
	  
	  public String serverID;
	  public short portNum;
	  public Link l;
	  public SOSPFPacket message;
	  
	  public HelloSocket(Link btwlink, SOSPFPacket msg) {
		  serverID = btwlink.router2.simulatedIPAddress;
		  portNum = btwlink.router2.processPortNumber;
		  message = msg;
	  }
	  
	  @Override
	  public void run() {
		  try {
			  System.out.println("Connecting to "+serverID+", "+portNum);
			  Socket client = new Socket(serverID, portNum);
			  System.out.println("Connected to "+serverID+", "+portNum);
			  OutputStream toServer = client.getOutputStream();
			  ObjectOutputStream out = new ObjectOutputStream(toServer);
			  out.writeObject(message);
			  System.out.println("HELLO message sent to "+serverID);
			  
			  // check response
			  InputStream serverResp = client.getInputStream();
			  ObjectInputStream in = new ObjectInputStream(serverResp);
			  SOSPFPacket response = (SOSPFPacket)in.readObject();
			  if (response.sospfType==0) {
				  for (Link l:ports) {
					  if (l.router2.simulatedIPAddress.equals(serverID)) {
						  // set router2 status and send HELLO again
						  l.router2.status = RouterStatus.TWO_WAY;
						  out.writeObject(message);
					  }
				  }
			  }
			  client.close();
		  }catch(ClassNotFoundException exp) {
			  System.out.println("No valid response message received");
		  }catch(IOException e){
			  e.printStackTrace();
		  }
  }  
  }
  
}




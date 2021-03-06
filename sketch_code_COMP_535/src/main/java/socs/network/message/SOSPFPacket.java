package socs.network.message;

import java.io.*;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class SOSPFPacket implements Serializable {

  //for inter-process communication
  public String srcProcessIP;
  public short srcProcessPort;

  //simulated IP address
  public String srcIP;
  public String dstIP;

  //common header
  public short sospfType; //0 - HELLO, 1 - LinkState Update
  public String routerID;

  //used by HELLO message to identify the sender of the message
  //e.g. when router A sends HELLO to its neighbor, it has to fill this field with its own
  //simulated IP address
  
  public String neighborID; //neighbor's simulated IP address
  							//used by LSAUPDATE to check who has already received the LSUPDATE
  

  public boolean originalTrigger;
  
  //used by LSAUPDATE
  public Vector<LSA> lsaArray = null;
  
  public SOSPFPacket(){ }

  public SOSPFPacket(short sospfType, String routerID, String neighborID, String srcIP, String dstIP, 
		  String srcProcessIP, short srcProcessPort){
	  this.sospfType = sospfType;
	  
	  this.routerID = routerID;
	  this.neighborID = neighborID;
	  
	  this.srcIP = srcIP;
	  this.dstIP = dstIP;
	  
	  this.srcProcessIP = srcProcessIP;
	  this.srcProcessPort = srcProcessPort;
  }
  
  public String toString(){
	  /*System.out.println("");
	  System.out.println("srcIP " + srcIP );
	  System.out.println("routerID " + routerID);
	  System.out.println("orig Trigger " + originalTrigger);
	  System.out.println("");*/
	  
	  return "\nsrcIP "+ srcIP + "\nrouterID " + routerID + "\norig Trigger " + originalTrigger + "\n";
  }
  
}

package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   */
  String getShortestPath(String destinationIP) {
    //TODO: fill the implementation here
	ArrayList<String> checked = new ArrayList<String>();
	ArrayList<String> unvisited = new ArrayList<String>();
	HashMap<String, NodeInfo> nodes = new HashMap<String, NodeInfo>();
	
	// add current rd to nodes list and checked list
	nodes.put(rd.simulatedIPAddress, new NodeInfo(0, null));
	checked.add(rd.simulatedIPAddress);
	
	// add neighbors of current router to unvisited list and initialize them in nodes list
	LSA current = _store.get(rd.simulatedIPAddress);
	for (LinkDescription l:current.links) {
		unvisited.add(l.linkID);
		nodes.put(l.linkID, new NodeInfo(l.tosMetrics, rd.simulatedIPAddress));
	}
	
	while (unvisited.size()>0) {
		String tocheck = getClosestNode(unvisited, nodes);// need sort
		
		if (checked.contains(tocheck)) continue;
//		System.out.println("checking "+tocheck);
		current = _store.get(tocheck);
		
		for (LinkDescription ld: current.links) {
			if (!nodes.containsKey(ld.linkID)) {
				nodes.put(ld.linkID, new NodeInfo(nodes.get(tocheck).distance+ld.tosMetrics, tocheck));
				unvisited.add(ld.linkID);
			}
			else if (nodes.get(ld.linkID).distance>nodes.get(tocheck).distance+ld.tosMetrics) 
				nodes.replace(ld.linkID, new NodeInfo(nodes.get(tocheck).distance+ld.tosMetrics,tocheck));
		
		}
		
		checked.add(tocheck);
		
	}
	
	// print out the shortest path
	String result = destinationIP;
	NodeInfo backtrackNode = nodes.get(destinationIP);
	String prevIP = destinationIP;
	
	while (!prevIP.equals(rd.simulatedIPAddress)) {
		prevIP = backtrackNode.prev;
		int linkWeight = backtrackNode.distance - nodes.get(prevIP).distance;
		result = new String(" ->(" + linkWeight + ") " + result);
		
		backtrackNode = nodes.get(prevIP);
	}
	
	result = new String(rd.simulatedIPAddress+result);
	System.out.println(result);
	System.out.print(">>");
	
    return result;
  }
  
  // a helper class for getShortestPath method
  class NodeInfo {
		int distance;
		String prev;

		public NodeInfo(int distance, String prev){
			this.distance = distance;
			this.prev = prev;
		}
	}

  // a helper method to return the unvisited node to be processed next 
  public String getClosestNode(ArrayList<String> checking, HashMap<String, NodeInfo> info) {
	  String toProcess = "";
	  int dis = Integer.MAX_VALUE;
	  for (String checkS: checking) {
		  NodeInfo tempNode = info.get(checkS);
		  if (tempNode.distance<dis && tempNode.distance!=0) {
			  toProcess = new String(checkS);
			  dis = tempNode.distance;
		  }
	  }
	  return toProcess;
  }
  

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.tosMetrics).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}

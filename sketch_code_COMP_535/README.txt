1. 
We assumed that if router A sends a HELLO message to router B but 
router B does not contain A as a neighbor yet, and B has an empty spot (
assuming B has not started yet). 
Then B will response with HELLO messages and add A as a neighbor.

2. 
Say B has A as a neighbor and A runs start with B as one of its neighbors.
In B, in the link with between B and A, A is set to TWO_WAY. 
When B runs start, we assume that B will not send a hello message to A because that would be unnecessary.


////////////////////////////////////////FOR ASSIGNMENT 2//////////////////////////////////////////////

Our code works on Windows system exactly as we expected. 

On Mac: There are some issues with running on Mac. Probably because mvn compile is "platform dependent".
When both routers involved in a connection are attached to each other prior to one running start, 
LSAUpdate messages are sent correctly. If Router A connects to Router B but Router B isn't already attached to Router A, 
Router B will receive Router A's LSAUpdate message but won't be able to connect to Router B to send their LSAUpdate. 
Eventually, there is an error closing the socket. This will cause issues in the LinkStateDatabase.
We are unsure of if this is a Mac issue or something is wrong with my set up.
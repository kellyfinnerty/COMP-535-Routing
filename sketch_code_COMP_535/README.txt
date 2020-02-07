1. 
We assumed that if router A sends a HELLO message to router B but 
router B does not contain A as a neighbor yet, and B has an empty spot (
assuming B has not started yet). 
Then B will response with HELLO messages and add A as a neighbor.

2. 
Say B has A as a neighbor and A runs start with B as one of its neighbors.
In B, in the link with between B and A, A is set to TWO_WAY. 
When B runs start, we assume that B will not send a hello message to A because that would be unnecessary.
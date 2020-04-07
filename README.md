# Phi-Accrual Failure Detector
This is a port of Akka's phi accrual failure detector. Their implementation is tightly coupled with akka-cluster, due to which it's not possible to use it as a stand alone failure detector.


##Todo
-> Implement PhiAccrual as an actor using UDP protocol

## Bugs
1. There is a peculiar case when the failure detector wrongly detects a process to have failed if the inter-arrival intervals are 0.
  



# References
[Phi Accrual](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.80.7427&rep=rep1&type=pdf)
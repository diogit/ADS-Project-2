All of our processes have a command interpreter which allows a user to manually execute certain commands in the system by sending them to the Client layer which will in turn send the operations to the App. The list of the main commands follows:
* `read <key>`: Reads the value associated with the given key
* `write <key> <value>`: Writes the given value for the given key and returns the previous value, if any
* `add <replica>`: Adds a replica to the system
* `remove <replica>`: Removes a replica from the system
* `test <#ops>`: Generate #ops operations to test

Most of our initial evaluation (and our evaluation in general) was based on running the first four commands manually in order to test the various scenarios that might occur during the execution of the program, from the simple read/writes to checking if the system remains functional when the leader process is terminated and the replica membership changes. During this period, our tests, albeit not really scalable, were able to verify the correctness of the program's execution.

Afterwards, we implemented the `test` command, which automatically sends write/read operations (with a distribution of 50% each) to the Client layer in order to simulate a real world application that must be able to handle a big load. All of these operations will eventually go through Multi Paxos in order to maintain the State Machine Replication consistent and once the results are computed and returned to the client statistics about the latency of each operation will be calculated.

Finally to further automate this last kind of testing, a shell script named `test.sh` (only tested on git-bash for Windows 10) was created so it can launch the initial set of replicas of the service, as well as a set of client which will automatically run the aforementioned `test` command with a specified number of operations.

As for the captured stats, we only captured the timestamp for when each operation is created, when it is sent the first time to the app and when its result is received. This allows us to calculate the On Creation Latency (End - StartOnCreation), On Send Latency (End - StartOnSend), Successful Ops Count, Average on Creation Latency (On Creation Latency / Successful Ops Count), Average on Send Latency (On Send Latency / Successful Ops Count), Throughput (Successful Ops Count / (On Send Latency / 1000)). The results for the last three, when varying the number of clients and operations, are displayed in Table 1. Some notes regarding these results: the number of decimal places for throughput was noticeably increased mid-testing; not many tests were made so there might be outliers in the data.

\# Clients | \# Ops | AvgCreationLatency | AvgSendLatency | Difference | Throughput
======
01 | 50 | 3214 ms | 113 ms | 3101 ms | 10 ops/s
01 | 100 | 5931 ms | 107 ms | 5824 ms | 10 ops/s
01 | 1000 | 43972 ms | 63 ms | 43909 ms | 15 ops/s
05 | 50 | 14167 ms | 516 ms | 13651 ms | 2 ops/s
05 | 100 | 25652 ms | 458 ms | 25194 ms | 2 ops/s
05 | 1000 | 104112 ms | 154 ms | 103958 ms | 6 ops/s
10 | 50 | 37314 ms | 1285 ms | 36029 ms | 0.777 ops/s
10 | 100 | 56369 ms | 871 ms | 55498 ms | 1.146 ops/s
10 | 1000 | 224896 ms | 431 ms | 224465 ms | 2.317 ops/s
20 | 50 | 44322 ms | 1323 ms | 42999 ms | 0.755 ops/s
20 | 100 | 89471 ms | 1343 ms | 88128 ms | 0.744 ops/s
20 | 1000 | 317672 ms | 568 ms | 317104 ms | 1.757 ops/s





01 > (Total) Count: 50 | Avg. On Creation Latency: 3214 ms | Avg. On Send Latency: 113 ms | Difference: 3101 ms | Throughput: 10 ops/s
01 > (Total) Count: 100 | Avg. On Creation Latency: 5931 ms | Avg. On Send Latency: 107 ms | Difference: 5824 ms | Throughput: 10 ops/s
01 > (Total) Count: 1000 | Avg. On Creation Latency: 43972 ms | Avg. On Send Latency: 63 ms | Difference: 43909 ms | Throughput: 15 ops/s
05 > (Total) Count: 50 | Avg. On Creation Latency: 14167 ms | Avg. On Send Latency: 516 ms | Difference: 13651 ms | Throughput: 2 ops/s
05 > (Total) Count: 100 | Avg. On Creation Latency: 25652 ms | Avg. On Send Latency: 458 ms | Difference: 25194 ms | Throughput: 2 ops/s
05 > (Total) Count: 1000 | Avg. On Creation Latency: 104112 ms | Avg. On Send Latency: 154 ms | Difference: 103958 ms | Throughput: 6 ops/s
10 > (Total) Count: 50 | Avg. On Creation Latency: 37314 ms | Avg. On Send Latency: 1285 ms | Difference: 36029 ms | Throughput: 0.777 ops/s
10 > (Total) Count: 100 | Avg. On Creation Latency: 56369 ms | Avg. On Send Latency: 871 ms | Difference: 55498 ms | Throughput: 1.146 ops/s
10 > (Total) Count: 1000 | Avg. On Creation Latency: 224896 ms | Avg. On Send Latency: 431 ms | Difference: 224465 ms | Throughput: 2.317 ops/s
20 > (Total) Count: 50 | Avg. On Creation Latency: 44322 ms | Avg. On Send Latency: 1323 ms | Difference: 42999 ms | Throughput: 0.755 ops/s
20 > (Total) Count: 100 | Avg. On Creation Latency: 89471 ms | Avg. On Send Latency: 1343 ms | Difference: 88128 ms | Throughput: 0.744 ops/s
20 > (Total) Count: 1000 | Avg. On Creation Latency: 317672 ms | Avg. On Send Latency: 568 ms | Difference: 317104 ms | Throughput: 1.757 ops/s
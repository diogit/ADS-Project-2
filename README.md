Implementation of a replicated service using State Machine Replication relying on the Multi Paxos protocol.

# MADE BY #

* Daniel Flamino (45465)
* Diogo Silvério (45679)
* Rita Macedo (46033)

--------------------------------------------------------------------------------

# FOLDER CONTENT #

* README.md -> this file
* docs/2nd Phase Report.pdf -> the project report
* akka-project/ -> the actual project
* akka-project/src/main/resources/aplication.conf -> application configuration
* akka-project/test.sh -> script to launch the initial replicas and clients
* akka-project/target/pack/bin/main -> the application (after compiling)

--------------------------------------------------------------------------------

# NOTES #

* The test.sh script was made to run on git-bash for Windows and might not work
for other OS/terminal combos. It uses an hard coded IP addresses and ports for
the tester which must be changed before running it. Same applies to the 
aplication.conf file with the set of initial replicas

* The project must be compiled using `sbt pack` before running it.

* For a list of available project commands, type `help` once the app is up and
running.

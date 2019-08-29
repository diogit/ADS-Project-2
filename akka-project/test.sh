start " " ".\target\pack\bin\main" 1 127.0.0.1 45679
start " " ".\target\pack\bin\main" 2 127.0.0.1 45465
start " " ".\target\pack\bin\main" 3 127.0.0.1 46033
start " " ".\target\pack\bin\main" 4 127.0.0.1 45234
sleep 5 # Wait before launching the clients
for port in {4400..4409} # Launch 10 clients
do
	start " " ".\target\pack\bin\main" $port 127.0.0.1 $port 50 # 50 operations
done
